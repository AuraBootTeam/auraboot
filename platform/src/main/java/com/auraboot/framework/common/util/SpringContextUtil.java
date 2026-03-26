package com.auraboot.framework.common.util;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author 高海军 帝奇 Apr 19, 2015 12:40:57 PM
 */
@Component
public class SpringContextUtil implements ApplicationContextAware, BeanFactoryPostProcessor {

    private static  ApplicationContext applicationContext;
    private ConfigurableListableBeanFactory factory;

    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
        this.factory = factory;
    }

    public void setApplicationContext(ApplicationContext c) throws BeansException {
        this.applicationContext = c;
    }

//    public static Object getBean(String name) {
//        return applicationContext.getBean(name);
//    }

    public static <T> T getBean(Class<T> clazz) {
        return applicationContext.getBean(clazz);
    }

    public static <T> T getBean(String name, Class<T> clazz) {
        return applicationContext.getBean(name, clazz);
    }

    public static <T> Map<String, T> getBeansOfType(Class<T> clazz) {
        return applicationContext.getBeansOfType(clazz);
    }

    public ConfigurableListableBeanFactory getFactory() {
        return factory;
    }

    public void setFactory(ConfigurableListableBeanFactory factory) {
        this.factory = factory;
    }

}
