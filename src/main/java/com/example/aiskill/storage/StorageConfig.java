package com.example.aiskill.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 配置类，负责创建 {@link StorageService} Bean。
 *
 * <p>通过 {@code storage.type} 配置项选择存储后端：</p>
 * <ul>
 *     <li>{@code ftp}（默认）：使用 {@link FtpStorageService}，需要一台可访问的 FTP 服务器</li>
 *     <li>{@code local}：使用 {@link LocalStorageService}，读写本地文件系统，无外部依赖</li>
 * </ul>
 *
 * <p>FTP 后端从 {@code storage.ftp.*} 读取配置：</p>
 * <ul>
 *     <li>{@code storage.ftp.host}：FTP 服务器主机地址，默认 {@code localhost}</li>
 *     <li>{@code storage.ftp.port}：FTP 服务器端口，默认 {@code 21}</li>
 *     <li>{@code storage.ftp.username}：FTP 登录用户名，默认为空</li>
 *     <li>{@code storage.ftp.password}：FTP 登录密码，默认为空</li>
 *     <li>{@code storage.ftp.base-dir}：存储根目录的绝对路径，默认 {@code /skills}</li>
 * </ul>
 *
 * <p>本地后端从 {@code storage.local.base-dir} 读取存储根目录，默认 {@code ./data/skills}。</p>
 *
 * <p>这些配置项通常在 {@code application.yml} 或 {@code application.properties} 中定义。</p>
 *
 * @see FtpStorageService
 * @see LocalStorageService
 */
@Slf4j
@Configuration
public class StorageConfig {

    /**
     * 创建 FTP 存储后端（{@code storage.type=ftp}，也是未显式配置时的默认值）。
     *
     * <p>从 Spring 配置属性中注入 FTP 连接参数，并传入 {@link FtpStorageService} 构造方法。
     * Bean 创建后会触发 {@link FtpStorageService#init()} 方法进行存储后端初始化。</p>
     *
     * @param host     FTP 服务器主机地址（默认 {@code localhost}）
     * @param port     FTP 服务器端口（默认 {@code 21}）
     * @param username FTP 登录用户名（默认为空）
     * @param password FTP 登录密码（默认为空）
     * @param baseDir  存储根目录的绝对路径（默认 {@code /skills}）
     * @return 配置完成的 {@link StorageService} 实例
     */
    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "ftp", matchIfMissing = true)
    public StorageService ftpStorageService(
            @Value("${storage.ftp.host:localhost}") String host,
            @Value("${storage.ftp.port:21}") int port,
            @Value("${storage.ftp.username:}") String username,
            @Value("${storage.ftp.password:}") String password,
            @Value("${storage.ftp.base-dir:/skills}") String baseDir) {
        log.info("Creating FtpStorageService (host={}, port={}, base-dir={})", host, port, baseDir);
        return new FtpStorageService(host, port, username, password, baseDir);
    }

    /**
     * 创建本地文件系统存储后端（{@code storage.type=local}）。
     *
     * <p>Bean 创建后会触发 {@link LocalStorageService#init()} 创建存储根目录。
     * 部署到容器平台时，{@code base-dir} 应指向持久化磁盘的挂载点，
     * 否则实例重启后数据会丢失。</p>
     *
     * @param baseDir 存储根目录，可为相对或绝对路径（默认 {@code ./data/skills}）
     * @return 配置完成的 {@link StorageService} 实例
     */
    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "local")
    public StorageService localStorageService(
            @Value("${storage.local.base-dir:./data/skills}") String baseDir) {
        log.info("Creating LocalStorageService (base-dir={})", baseDir);
        return new LocalStorageService(baseDir);
    }
}
