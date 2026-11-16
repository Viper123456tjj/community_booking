package com.community.booking.controller;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.service.ServiceItemService;
import com.community.booking.utils.FileUploadUtils;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 附件接口（/api/file/**，任意已登录角色可访问；上传封面仅 ROLE_ADMIN）。
 * <p>本系统的附件场景只有一种：服务项目封面图（service_item.cover_img）。
 * 格式与大小校验全部在 FileUploadUtils 内完成（图片扩展名白名单 + 50MB 上限 +
 * UUID 重命名落盘），本 Controller 只负责按业务拼装子目录与调用 Service 回写；
 * 项目存在性与"未设封面"的 404 判定收口在 ServiceItemService.getCoverPath，
 * 因此本类不注入任何 Mapper，遵守"Controller 严禁跨层访问 Mapper"的分层铁律。</p>
 * <p>上传语义为"落盘 + 回写一次完成"：管理员选图即上传即生效，不必再提交一次
 * 表单——ItemSaveDTO 刻意不含 coverImg 字段，封面路径不随表单往返，避免
 * "编辑其他字段时忘了带封面导致封面被清空"这类经典缺陷。</p>
 * <p>读取安全（文件维度的 IDOR 等价实现）三重防线：① 登录即可（未登录 401）；
 * ② FileUploadUtils.load 校验相对路径必须位于上传根目录内（拦截 ../ 与绝对
 * 路径注入）；③ 落盘文件名是 32 位 UUID，不可枚举、不可猜测——"知道路径即
 * 拥有权限"（capability URL），而路径只会通过 Service 层 IDOR 校验后的 VO
 * 下发给前端，越权用户根本拿不到路径。</p>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，读取返回文件流、上传返回 Result JSON
@RestController
// 文件接口前缀：SecurityConfig 归入 anyRequest().authenticated()
@RequestMapping("/api/file")
// 构造器注入附件工具与项目业务（无 Mapper，分层铁律）
@RequiredArgsConstructor
public class FileController {

    /** 安全文件上传下载工具（白名单校验 + UUID 落盘 + 路径归属校验） */
    private final FileUploadUtils fileUploadUtils;

    /** 服务项目业务：封面回写与封面路径定位全部走 Service 层 */
    private final ServiceItemService itemService;

    /**
     * 上传服务项目封面图：落盘后立即回写 cover_img，返回入库路径供前端预览。
     * <p>先落盘再回写：回写失败时仅产生一个孤儿附件文件（可由运维清理），
     * 不会出现"记录指向不存在的文件"；反向顺序则可能出现失效引用。</p>
     *
     * @param file   图片文件（格式与大小由 FileUploadUtils 白名单校验）
     * @param itemId 目标服务项目ID（须真实存在，Service 层校验）
     * @return 成功时 data 为相对存储路径（如 cover/12/3f2a....png）
     */
    @PostMapping("/cover")
    // 封面写权限仅管理员（与目录管理接口同一角色口径，纵深防御）
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> uploadCover(
            @RequestParam("file") MultipartFile file,
            @RequestParam("itemId") Long itemId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        String relativePath = fileUploadUtils.save(file, "cover/" + itemId);
        itemService.saveCoverImage(itemId, relativePath);
        log.info("管理员[{}]上传服务[{}]封面 {} -> {}", operator.getUsername(), itemId,
                file.getOriginalFilename(), relativePath);
        return Result.success("封面已上传并生效", relativePath);
    }

    /**
     * 按项目ID直读封面图（供前端 {@code <img src="/api/file/cover-image/{id}">} 内联加载）。
     * <p>与 download 的区别：本接口 Content-Disposition: inline 且按扩展名给出
     * 真实 MIME 类型，浏览器会渲染为图片而非触发下载；封面是服务项目的公开展示图，
     * 三类角色登录后都能看，无需归属校验（数据本身公开，路径由 VO 下发）。</p>
     * <p>项目无封面时回 404，前端据返回码回退默认占位图标。</p>
     *
     * @param itemId 服务项目ID
     * @return 图片流响应（inline）
     */
    @GetMapping("/cover-image/{itemId}")
    public ResponseEntity<FileSystemResource> coverImage(@PathVariable Long itemId) {
        // 路径定位与"不存在/未设封面"的 404 判定在 Service 层完成（不注入 Mapper）
        String coverPath = itemService.getCoverPath(itemId);
        FileSystemResource resource = fileUploadUtils.load(coverPath);
        return ResponseEntity.ok()
                // inline：浏览器内联渲染为图片（attachment 会触发另存为对话框）
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\""
                        + (resource.getFilename() == null ? "cover" : resource.getFilename()) + "\"")
                .contentType(guessImageType(coverPath))
                .body(resource);
    }

    /**
     * 下载附件（以入库相对路径为锚点，三重安全防线见类注释）。
     *
     * @param path 入库的相对存储路径（FileUploadUtils.save 的返回值）
     * @param name 可选的原始文件名（仅用于浏览器另存为展示，做注入清洗）
     * @return 文件流响应（Content-Disposition: attachment）
     */
    @GetMapping("/download")
    public ResponseEntity<FileSystemResource> download(
            @RequestParam("path") String path,
            @RequestParam(value = "name", required = false) String name) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        // load 内部完成：非空校验 + 路径归属校验 + 存在性校验
        FileSystemResource resource = fileUploadUtils.load(path);
        // 展示文件名：优先用调用方回传的原始名（清洗防 Header 注入），否则用落盘名
        String displayName = sanitizeName(name);
        if (displayName == null) {
            displayName = resource.getFilename() == null ? "download" : resource.getFilename();
        }
        log.info("用户 {} 下载附件 {}", operator.getUsername(), path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + displayName + "\"; filename*=UTF-8''"
                                + URLEncoder.encode(displayName, StandardCharsets.UTF_8))
                .contentType(guessImageType(path))
                .body(resource);
    }

    /**
     * 按扩展名推断图片 MIME 类型（扩展名已由 FileUploadUtils 白名单收口，
     * 非图片扩展名一律回退 application/octet-stream，杜绝把未知类型当图片渲染）。
     *
     * @param relativePath 相对存储路径
     * @return MIME 类型
     */
    private MediaType guessImageType(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * 清洗展示文件名：去除引号/换行等可能破坏 Header 的字符并限长。
     *
     * @param raw 调用方回传的原始文件名（可为 null）
     * @return 安全文件名；空白时返回 null（由调用方回退落盘名）
     */
    private String sanitizeName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        // 去掉双引号、反斜杠与控制字符（CR/LF 是 Header 注入的主要载体）
        String cleaned = raw.replace("\"", "").replace("\\", "").replaceAll("[\\x00-\\x1F]", "");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
