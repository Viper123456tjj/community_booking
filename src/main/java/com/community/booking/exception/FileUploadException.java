package com.community.booking.exception;

/**
 * 文件上传/下载异常（400）：图片格式不在白名单、超过大小上限、路径穿越攻击、
 * 落盘 IO 失败等场景由 FileUploadUtils 抛出。
 *
 * @author community-booking
 */
public class FileUploadException extends BusinessException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造 400 的文件操作异常。
     *
     * @param msg 具体中文提示（如"不支持的图片格式，仅允许 png/jpg/jpeg"）
     */
    public FileUploadException(String msg) {
        super(400, msg);
    }
}
