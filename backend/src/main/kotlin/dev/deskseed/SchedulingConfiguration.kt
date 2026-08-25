package dev.deskseed

import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.config.TaskManagementConfigUtils

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class SchedulingConfiguration {
    companion object {
        /**
         * `@EnableScheduling` registers its processor while configuration classes are parsed. Remove that
         * canonical processor before bean post-processors are instantiated when the master switch is off.
         */
        @Bean
        @JvmStatic
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnProperty(
            prefix = "deskseed.scheduling",
            name = ["enabled"],
            havingValue = "false",
        )
        fun schedulingInfrastructureDisabler(): BeanFactoryPostProcessor = BeanFactoryPostProcessor { beanFactory ->
            val registry = beanFactory as BeanDefinitionRegistry
            val processorName = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME
            if (registry.containsBeanDefinition(processorName)) {
                registry.removeBeanDefinition(processorName)
            }
        }
    }
}
