package com.jiangpa.common;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public Result(Integer code, String message, T data) {
        this.data = data;
        this.code = code;
        this.message = message;
    }

    public Result() {

    }

    //成功时返回
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    //失败时返回

    //1.参数错误：必填字段为空，参数不合法
    public static <T> Result<T> paramError(String message) {
        return new Result<>(400, message, null);
    }

    //2.资源不存在:按 id 查询／修改／删除时找不到记录
    public static <T> Result<T> notFound(String message) {
        return new Result<>(404, message, null);
    }

    //3.无权限：修改删除别人文章时无权限
    public static <T> Result<T> forbidden(String message) {
        return new Result<>(403, message, null);
    }

    //4.未登录或登录已过期
    public static <T> Result<T> unauthorized(String message) {
        return new Result<>(401, message, null);
    }

    //5.服务器内部错误:未预期的异常
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }

    //6.自定义返回
    public static <T> Result<T> build(Integer code, String message, T data) {
        return new Result<>(code, message, data);
    }


}
