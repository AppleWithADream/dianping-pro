package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一拦截 Controller 层抛出的运行时异常，
 * 记录错误日志并向前端返回友好提示，避免把堆栈直接暴露给用户。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    // 捕获所有 RuntimeException，打印日志后返回"服务器异常"
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
