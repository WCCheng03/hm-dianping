package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession();
        //获取session中的用户
        UserDTO user = (UserDTO) session.getAttribute("user");
        //判断用户是否存在
        if (user ==null) {
            //不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }
        //存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(user);
        //放行
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户，避免内存泄漏
        UserHolder.removeUser();
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
