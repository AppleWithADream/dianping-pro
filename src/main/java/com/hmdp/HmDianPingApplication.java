package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 启动类：Spring Boot 工程入口。
 * @EnableAspectJAutoProxy(exposeProxy=true) 暴露代理对象，业务中可用 AopContext.currentProxy()
 *         拿到自身代理，保证同类内部方法调用时 @Transactional 等注解仍然生效；
 * @MapperScan 扫描 Mapper 接口并注册为 Bean。
 */
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
