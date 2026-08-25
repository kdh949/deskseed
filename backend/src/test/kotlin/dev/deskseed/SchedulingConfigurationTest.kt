package dev.deskseed

import dev.deskseed.outboundmail.internal.MailDeliveryScheduler
import dev.deskseed.outboundmail.internal.MailDeliveryWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import java.util.concurrent.atomic.AtomicInteger

@dev.deskseed.testsupport.category.FastTest
class SchedulingConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfiguration::class.java)

    @Test
    fun `scheduling infrastructure is enabled when the property is missing`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor::class.java)
        }
    }

    @Test
    fun `scheduling infrastructure is enabled when the property is true`() {
        contextRunner
            .withPropertyValues("deskseed.scheduling.enabled=true")
            .run { context ->
                assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `scheduling infrastructure is absent when the property is false`() {
        contextRunner
            .withPropertyValues("deskseed.scheduling.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `the common test configuration disables scheduling`() {
        val properties = PropertiesLoaderUtils.loadProperties(ClassPathResource("application.properties"))

        assertThat(properties.getProperty("deskseed.scheduling.enabled")).isEqualTo("false")
    }

    @Test
    fun `a disabled context never runs scheduled workers in the background`() {
        contextRunner
            .withUserConfiguration(ScheduledProbeConfiguration::class.java)
            .withPropertyValues("deskseed.scheduling.enabled=false")
            .run { context ->
                val counter = context.getBean(AtomicInteger::class.java)
                Thread.sleep(100)
                assertThat(counter).hasValue(0)
            }
    }

    @Test
    fun `closing the context stops scheduled worker execution`() {
        var counter: AtomicInteger? = null
        contextRunner
            .withUserConfiguration(ScheduledProbeConfiguration::class.java)
            .withPropertyValues("deskseed.scheduling.enabled=true")
            .run { context ->
                val runningCounter = context.getBean(AtomicInteger::class.java)
                counter = runningCounter
                waitForFirstExecution(runningCounter)
            }

        val stoppedAt = counter!!.get()
        Thread.sleep(100)
        assertThat(counter).hasValue(stoppedAt)
    }

    @Test
    fun `mail scheduler still requires both module specific enable properties`() {
        val mailSchedulerRunner = ApplicationContextRunner()
            .withUserConfiguration(MailSchedulerTestConfiguration::class.java)

        mailSchedulerRunner
            .withPropertyValues(
                "deskseed.mail.delivery-enabled=true",
                "deskseed.mail.scheduling-enabled=true",
            )
            .run { context -> assertThat(context).hasSingleBean(MailDeliveryScheduler::class.java) }

        mailSchedulerRunner
            .withPropertyValues(
                "deskseed.mail.delivery-enabled=true",
                "deskseed.mail.scheduling-enabled=false",
            )
            .run { context -> assertThat(context).doesNotHaveBean(MailDeliveryScheduler::class.java) }
    }

    private fun waitForFirstExecution(counter: AtomicInteger) {
        repeat(100) {
            if (counter.get() > 0) return
            Thread.sleep(10)
        }
        throw AssertionError("Scheduled probe did not execute")
    }
}

@TestConfiguration(proxyBeanMethods = false)
private class ScheduledProbeConfiguration {
    @Bean
    fun scheduledProbeCounter(): AtomicInteger = AtomicInteger()

    @Bean
    fun scheduledProbe(counter: AtomicInteger): ScheduledProbe = ScheduledProbe(counter)
}

private class ScheduledProbe(private val counter: AtomicInteger) {
    @Scheduled(fixedDelay = 10)
    fun execute() {
        counter.incrementAndGet()
    }
}

@TestConfiguration(proxyBeanMethods = false)
@Import(MailDeliveryScheduler::class)
private class MailSchedulerTestConfiguration {
    @Bean
    fun mailDeliveryWorker(): MailDeliveryWorker = mock(MailDeliveryWorker::class.java)
}
