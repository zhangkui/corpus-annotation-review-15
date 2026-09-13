import com.google.inject.AbstractModule
import messaging.{EventConsumer, EventPublisher}
import play.api.{Configuration, Environment}

/** 启动时即初始化 RabbitMQ 发布者与消费者 */
class Module(environment: Environment, configuration: Configuration) extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[EventPublisher]).asEagerSingleton()
    bind(classOf[EventConsumer]).asEagerSingleton()
  }
}
