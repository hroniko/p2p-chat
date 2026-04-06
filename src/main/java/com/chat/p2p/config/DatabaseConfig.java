package com.chat.p2p.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.io.File;

@Configuration
public class DatabaseConfig {
    
    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        
        String dbPath = System.getProperty("user.dir") + File.separator + "chat.db";
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        
        return dataSource;
    }
}
