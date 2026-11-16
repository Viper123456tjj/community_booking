package com.community.booking.controller;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PasswordUpdateDTO;
import com.community.booking.dto.UserProfileSaveDTO;
import com.community.booking.service.SysUserService;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import com.community.booking.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人中心接口（/api/user/**，三类角色登录即可访问）。
 * <p>本组接口对居民、师傅、管理员都有用途（都要维护姓名/电话/邮箱，
 * 邮箱是派单通知与次日提醒邮件的收件地址），因此不按角色划分 URL，
 * 而是"登录即可 + Service 层归属校验"：</p>
 * <ul>
 *   <li>接口不接受任何 userId 参数——操作对象恒为当前登录用户，
 *       水平越权（IDOR）在 URL 层面就无从发生；</li>
 *   <li>资料表单严禁携带 role/status 字段（UserProfileSaveDTO 已刻意
 *       不含这两项），普通用户无法借资料表单自我提权或自我禁用。</li>
 * </ul>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 个人中心前缀：SecurityConfig 归入 anyRequest().authenticated()
@RequestMapping("/api/user")
// 构造器注入用户业务服务
@RequiredArgsConstructor
public class UserController {

    /** 用户业务服务：资料维护、密码维护、当前用户信息 */
    private final SysUserService sysUserService;

    /**
     * 查询当前登录用户的资料（个人中心回显）。
     *
     * @return 当前用户脱敏 VO（不含密码）
     */
    @GetMapping("/info")
    public Result<UserVO> info() {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(sysUserService.getCurrentUserInfo(operator));
    }

    /**
     * 修改当前登录用户的个人资料（姓名/手机号/邮箱/地址）。
     *
     * @param dto 资料表单（JSR-303 校验姓名、邮箱格式、手机号格式）
     * @return 成功提示
     */
    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody @Validated UserProfileSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.updateProfile(operator, dto);
        log.info("用户[{}]修改个人资料", operator.getUsername());
        return Result.success("资料已保存", null);
    }

    /**
     * 修改当前登录用户的密码（校验旧密码后 BCrypt 加密保存）。
     *
     * @param dto 密码表单（旧密码 + 新密码，JSR-303 校验长度）
     * @return 成功提示
     */
    @PutMapping("/password")
    public Result<Void> updatePassword(@RequestBody @Validated PasswordUpdateDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.updatePassword(operator, dto.getOldPassword(), dto.getNewPassword());
        log.info("用户[{}]修改密码", operator.getUsername());
        return Result.success("密码已修改，请重新登录", null);
    }
}
