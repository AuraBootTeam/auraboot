package com.auraboot.framework.application.web.config;

import com.auraboot.framework.application.security.WhiteList;
import com.auraboot.framework.application.web.inteceptor.TenantInterceptor;
import com.auraboot.framework.application.web.resolver.CurrentUserIdResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private  CurrentUserIdResolver currentUserIdResolver;

//    @Autowired
//    private QueryRequestArgumentResolver queryRequestArgumentResolver;
    @Autowired
    private TenantInterceptor tenantInterceptor;

    // PermissionInterceptor已废弃,使用PermissionInterceptor代替(在WebMvcConfig中注册)




    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(WhiteList.whiteList); //todo use whitelist

        // PermissionInterceptor已废弃,使用PermissionInterceptor代替(在WebMvcConfig中注册)
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdResolver);
//        resolvers.add(queryRequestArgumentResolver);
    }

    // /**
    //  * 配置CORS跨域请求
    //  */
    // @Override
    // public void addCorsMappings(CorsRegistry registry) {
    //     registry.addMapping("/api/**")
    //             .allowedOriginPatterns("*") // 允许所有来源，生产环境应该指定具体域名
    //             .allowedMethods("get", "post", "put", "delete", "options", "patch") // 允许的HTTP方法
    //             .allowedHeaders("*") // 允许所有请求头
    //             .allowCredentials(true) // 允许携带认证信息
    //             .maxAge(3600); // 预检请求的缓存时间（秒）
    // }
}