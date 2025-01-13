package com.youlai.boot.shared.auth.service;

import com.youlai.boot.shared.auth.model.CaptchaResponse;
import com.youlai.boot.core.security.model.AuthToken;
import com.youlai.boot.shared.auth.model.RefreshTokenRequest;

/**
 * 认证服务接口
 *
 * @author haoxr
 * @since 2.4.0
 */
public interface AuthService {

    /**
     * 登录
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录结果
     */
    AuthToken login(String username, String password);

    /**
     * 登出
     */
    void logout();

    /**
     * 获取验证码
     *
     * @return 验证码
     */
    CaptchaResponse getCaptcha();

    /**
     * 刷新令牌
     *
     * @param request 刷新令牌请求参数
     * @return 登录结果
     */
    AuthToken refreshToken(RefreshTokenRequest request);

    /**
     * 微信小程序登录
     *
     * @param code 微信登录code
     * @return 登录结果
     */
    AuthToken wechatLogin(String code);

    /**
     * 发送短信验证码
     *
     * @param mobile 手机号
     */
    void sendLoginVerifyCode(String mobile);
}
