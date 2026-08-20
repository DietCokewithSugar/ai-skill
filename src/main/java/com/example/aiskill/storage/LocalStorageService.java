package com.example.aiskill.storage;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于本地文件系统实现的 {@link StorageService} 存储服务。
 *
 * <p>存储根目录由 {@code storage.local.base-dir} 配置项指定。相较于
 * {@link FtpStorageService}，本实现不依赖任何外部服务，适用于将应用部署到
 * PaaS 平台（如 Render）并挂载持久化磁盘的场景。</p>
 *
 * <p><b>持久化提醒：</b>容器化平台的默认文件系统通常是临时的，实例重启或重新部署后
 * 数据会丢失。使用本实现时，请将 {@code base-dir} 指向一块持久化磁盘的挂载点。</p>
 *
 * <p>对外暴露的路径语义与 {@link FtpStorageService} 完全一致：均为相对于
 * {@code base-dir} 的相对路径，统一使用 {@code /} 作为分隔符。</p>
 *
 * <p>该类由 {@link StorageConfig} 作为 Spring Bean 进行实例化，因此<b>故意</b>没有
 * 添加 {@code @Component} 注解。</p>
 *
 * @see StorageService
 * @see StorageConfig
 */
@Slf4j
public class LocalStorageService implements StorageService {

    /** 存储根目录（已转为规范化的绝对路径）。 */
    private final Path root;

    /**
     * 构造方法，初始化本地存储的根目录。
     *
     * @param baseDir 存储根目录，可为相对或绝对路径
     */
    public LocalStorageService(String baseDir) {
        String dir = (baseDir == null || baseDir.trim().isEmpty()) ? "./data/skills" : baseDir.trim();
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /**
     * 规范化相对路径：使用 {@code /} 作为分隔符，并移除首尾斜杠。
     *
     * <p>语义与 {@link FtpStorageService} 保持一致：空路径、{@code null} 或根路径
     * {@code /} 均视为空字符串（即根目录）。</p>
     *
     * @param path 待规范化的相对路径
     * @return 规范化后的相对路径（无前导/尾部斜杠），根目录返回空字符串
     */
    private static String normalizeRelative(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "";
        }
        String p = path.replace("\\", "/");
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/") && p.length() > 0) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 将相对存储路径解析为本地文件系统的绝对路径。
     *
     * <p>解析结果会做越界校验：任何借助 {@code ..} 逃逸出存储根目录的路径都会被拒绝，
     * 避免调用方传入的路径读写到根目录之外。</p>
     *
     * @param path 相对存储路径
     * @return 位于存储根目录内的绝对路径
     * @throws IllegalArgumentException 如果路径逃逸出存储根目录
     */
    private Path resolve(String path) {
        String relative = normalizeRelative(path);
        Path target = relative.isEmpty() ? root : root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("路径越界，拒绝访问存储根目录之外的位置: " + path);
        }
        return target;
    }

    /**
     * 确保目标路径的父目录存在。
     *
     * @param target 目标文件路径
     * @throws IOException 如果创建父目录失败
     */
    private void ensureParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void store(String path, byte[] content) throws IOException {
        Path target = resolve(path);
        ensureParent(target);
        Files.write(target, content);
    }

    /** {@inheritDoc} */
    @Override
    public void storeStream(String path, InputStream inputStream) throws IOException {
        Path target = resolve(path);
        ensureParent(target);
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@inheritDoc} */
    @Override
    public byte[] read(String path) throws IOException {
        Path target = resolve(path);
        if (!Files.isRegularFile(target)) {
            throw new IOException("文件不存在: " + path);
        }
        return Files.readAllBytes(target);
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(String path) {
        try {
            return Files.exists(resolve(path));
        } catch (IllegalArgumentException e) {
            // 越界路径一律视为不存在，与 FTP 实现的容错行为保持一致
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String path) throws IOException {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            return; // 不存在时静默返回
        }
        if (Files.isDirectory(target)) {
            // 先收集再删除：避免在遍历流未关闭时修改目录树
            List<Path> entries;
            try (Stream<Path> walk = Files.walk(target)) {
                entries = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        } else {
            Files.deleteIfExists(target);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void createDirectory(String path) throws IOException {
        Files.createDirectories(resolve(path));
    }

    /** {@inheritDoc} */
    @Override
    public List<String> listDirectories(String dirPath) throws IOException {
        Path dir = resolve(dirPath);
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result; // 目录不存在，返回空列表
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    result.add(entry.getFileName().toString());
                }
            }
        }
        Collections.sort(result); // 排序保证结果稳定
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public List<FileInfo> listFiles(String dirPath) throws IOException {
        Path dir = resolve(dirPath);
        List<FileInfo> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result; // 目录不存在，返回空列表
        }
        listRecursive(dir, normalizeRelative(dirPath), result);
        return result;
    }

    /**
     * 递归收集目录下的所有条目（含子目录本身），构建相对于存储根目录的路径。
     *
     * <p>与 {@link FtpStorageService} 一致：目录条目的 size 记为 0，
     * 且目录本身也会作为一条记录出现在结果中。</p>
     *
     * @param dir         待扫描的绝对路径
     * @param relativeDir 该目录相对于存储根目录的相对路径
     * @param result      结果收集列表
     * @throws IOException 如果读取目录失败
     */
    private void listRecursive(Path dir, String relativeDir, List<FileInfo> result) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        }
        // 按名称排序，保证遍历顺序稳定
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            String relative = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
            if (Files.isDirectory(entry)) {
                result.add(new FileInfo(name, relative, 0L, true));
                listRecursive(entry, relative, result); // 递归进入子目录
            } else {
                result.add(new FileInfo(name, relative, Files.size(entry), false));
            }
        }
    }

    /**
     * 初始化本地存储：创建存储根目录（如不存在）。
     *
     * <p>由 Spring 的 {@link PostConstruct} 注解触发，在 Bean 创建后自动执行。
     * 初始化失败将抛出 {@link IllegalStateException} 阻止应用启动。</p>
     */
    @PostConstruct
    @Override
    public void init() {
        try {
            Files.createDirectories(root);
            log.info("Local storage initialized: {}", root);
        } catch (IOException e) {
            log.error("Failed to initialize local storage at {}: {}", root, e.getMessage(), e);
            throw new IllegalStateException("Failed to initialize local storage", e);
        }
    }
}
