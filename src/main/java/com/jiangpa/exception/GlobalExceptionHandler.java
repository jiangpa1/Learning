package com.jiangpa.exception;

import com.jiangpa.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolationException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        log.warn(e.getMessage(), e);
        if (e.getBindingResult().getFieldErrors().isEmpty()) {
            return Result.error("服务器错误");
        }
        return Result.paramError(e.getBindingResult().getFieldErrors().get(0).getDefaultMessage());
    }

    @ExceptionHandler(value = ConstraintViolationException.class)
    public Result<?> handleUnexpected(ConstraintViolationException e) {
        log.warn(e.getMessage(), e);
        return Result.paramError(e.getMessage());
    }

    @ExceptionHandler(value = DuplicateKeyException.class)
    public Result<?> handleDuplicateKey(DuplicateKeyException e) {
        log.warn(e.getMessage(), e);
        return Result.paramError("数据不可复用！");
    }

    @ExceptionHandler(value = BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        log.warn(e.getMessage(), e);
        return Result.build(e.getCode(), e.getMessage(),  null);
    }

    @ExceptionHandler(value = Exception.class)
    public Result<?> handleException(Exception e) {
        log.error(e.getMessage(), e);
        return Result.error("服务器开小差了，请稍后再试");
    }
}
