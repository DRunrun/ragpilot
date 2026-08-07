# Spring Bean Lifecycle (sample for future ingest)

Spring Beans have a managed lifecycle inside the ApplicationContext.

Main stages commonly discussed in interviews:

1. Instantiation
2. Populate properties
3. BeanNameAware / BeanFactoryAware callbacks
4. BeanPostProcessor.beforeInitialize
5. InitializingBean.afterPropertiesSet / custom init-method
6. BeanPostProcessor.afterInitialize
7. Bean in use
8. DisposableBean.destroy / custom destroy-method

This file is a tiny demo corpus for RagPilot M1 ingestion tests.
