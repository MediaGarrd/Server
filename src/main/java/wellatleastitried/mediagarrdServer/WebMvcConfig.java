package wellatleastitried.mediagarrdServer;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import wellatleastitried.mediagarrdServer.api.SetupInterceptor;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SetupInterceptor setupInterceptor;

    public WebMvcConfig(SetupInterceptor setupInterceptor) {
        this.setupInterceptor = setupInterceptor;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/config").setViewName("forward:/config.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(setupInterceptor);
    }
}
