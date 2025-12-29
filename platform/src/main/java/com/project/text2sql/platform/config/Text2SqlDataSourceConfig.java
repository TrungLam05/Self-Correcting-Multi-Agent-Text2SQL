package com.project.text2sql.platform.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({Text2SqlDbProperties.class, Text2SqlSchemaProperties.class, Text2SqlExecutorProperties.class})
public class Text2SqlDataSourceConfig {
    @Bean
    public DataSource text2sqlDataSource(Text2SqlDbProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());

        config.setPoolName("text2sql");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setReadOnly(true);

        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}