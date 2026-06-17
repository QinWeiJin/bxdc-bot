package com.lobsterai.skillgateway.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.logging.Logger;

@Configuration
public class MybatisPlusConfig {

    /**
     * 启动时把 JVM 默认时区设为 Asia/Shanghai（北京时间 UTC+8）。
     */
    @PostConstruct
    public void forceJvmUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    /**
     * 强制 MySQL session 时区与 JVM 保持一致（Asia/Shanghai）。
     *
     * 背景：MySQL 8.x Connector/J 的 serverTimezone=Asia/Shanghai 参数（JDBC URL 里）
     * 只影响 timestamp 转换，不真正设 MySQL session 的 time_zone 系统变量；
     * 而 session.time_zone 决定 NOW() / CURRENT_TIMESTAMP 返回的值。
     *
     * 后果：async poll 调度器用 NOW() 跟 last_polled_at 比较时（差 8 小时），
     * 任务只在首次扫描被扫到，之后永远不会再被轮询 —— 异步任务永远卡 POLLING。
     *
     * 修法：用 BeanPostProcessor 包装 auto-config 的 DataSource，每个 getConnection() 返回
     * 的 Connection 都先执行 SET time_zone='+08:00' 再返回。Hikari 复用连接时也会重设。
     *
     * 为什么不用 HikariDataSource.setConnectionInitSql：Hikari pool 启动后 setter 不生效，
     * 而 pool 在 DataSource bean 创建后立即 start（"HikariPool-1 - Start completed"），
     * 等 @PostConstruct 跑时 pool 已 start，setter 静默失效。
     */
    @Bean
    public static BeanPostProcessor mysqlSessionTimeZoneWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource) {
                    return new TimeZoneAwareDataSource((DataSource) bean);
                }
                return bean;
            }
        };
    }

    /**
     * 包装 DataSource：所有 getConnection() 返回的 Connection 都先执行 SET time_zone 再返回。
     * 其他方法透传给底层。
     */
    static class TimeZoneAwareDataSource implements DataSource {
        private final DataSource delegate;

        TimeZoneAwareDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return setSessionTimeZone(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return setSessionTimeZone(delegate.getConnection(username, password));
        }

        private Connection setSessionTimeZone(Connection c) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute("SET time_zone='+08:00'");
            }
            return c;
        }

        // --- 以下透传 ---
        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
