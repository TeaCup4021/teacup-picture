package com.teacup.teacuppicturebackend.aop;


import com.teacup.teacuppicturebackend.annotation.AuthCheck;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.enums.UserRoleEnum;
import com.teacup.teacuppicturebackend.service.UserService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.teacup.teacuppicturebackend.model.entity.User;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@Aspect
@Component
public class AuthInterceptor {

    @Resource
    private UserService userService;

    //工作流程：

    /* spring aop扫描所有被@Around注解标记的方法
    *  解析"@annotation(authCheck)"，发现authCheck是方法中的参数
    *  因为authCheck类名为AuthCheck，当执行到被@AuthCheck标记的方法时，触发当前切面方法
    *  自动将方法上的 @AuthCheck 注解实例作为参数传入
    *
    *  */

    //这里 authCheck 参数的类型是 AuthCheck，所以 @annotation(authCheck) 就是匹配所有被 @AuthCheck 注解标记的方法。
    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable{
        //获取需要验证的角色名称
        String mustRole = authCheck.mustRole();
        //使用Spring的RequestContextHolder获取当前线程的请求属性
        //获取当前请求的上下文
        RequestAttributes requestAttributes = RequestContextHolder.currentRequestAttributes();
        //获取HTTP请求对象
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();

        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 获取需要验证的角色
        UserRoleEnum mustRoleEnum = UserRoleEnum.getEnumByValue(mustRole);

        // 如果不需要特定权限，直接放行
        if (mustRoleEnum == null) {
            return joinPoint.proceed();
        }

        // 获取当前用户的角色
        UserRoleEnum userRoleEnum = UserRoleEnum.getEnumByValue(loginUser.getUserRole());

        // 如果没有权限，拒绝访问
        if (userRoleEnum == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 要求必须有管理员权限，但用户没有管理员权限，拒绝访问
        if (UserRoleEnum.ADMIN.equals(mustRoleEnum) && !UserRoleEnum.ADMIN.equals(userRoleEnum)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 通过权限校验，执行原方法
        return joinPoint.proceed();


    }
}
