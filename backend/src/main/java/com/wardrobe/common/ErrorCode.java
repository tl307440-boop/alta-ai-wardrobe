package com.wardrobe.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    SUCCESS(200, HttpStatus.OK, "success"),
    PARAM_INVALID(40001, HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    IMAGE_INVALID(40002, HttpStatus.BAD_REQUEST, "图片格式非法、文件损坏或超出 10MB 限制"),
    USERNAME_EXISTS(40003, HttpStatus.BAD_REQUEST, "用户名已存在，请更换用户名或直接登录"),
    UNAUTHORIZED(40101, HttpStatus.UNAUTHORIZED, "登录态无效或已过期，请重新登录"),
    BAD_CREDENTIALS(40102, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    FORBIDDEN(40301, HttpStatus.FORBIDDEN, "无权访问或操作他人的衣橱资源"),
    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "请求的资源不存在"),
    NOT_CLOTHING(42201, HttpStatus.UNPROCESSABLE_ENTITY, "未在图片中检测到清晰的服装主体，请重新上传衣物图"),
    AI_UPSTREAM_ERROR(50201, HttpStatus.BAD_GATEWAY, "多模态大模型调用超时或返回格式异常，请稍后重试或检查 API Key 配置"),
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部异常，请稍后重试");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
