package org.elasticsearch.test.management;

import java.lang.management.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * ManagementFactory——java监控和管理JVM
 * https://blog.csdn.net/hutongling/article/details/106607572
 * https://blog.csdn.net/GYongJia/article/details/104925362
 */
public class ManagementFactoryTest {

    public static void main(String[] args) {
        /**
         * 操作系统
         */
        System.out.println("*****************操作系统模块*****************");
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        // 获得JVM可用处理器数量
        int availableProcessors = os.getAvailableProcessors();
        // 操作系统的架构
        String arch = os.getArch();
        // 操作系统的名称
        String name = os.getName();
        // 操作系统上一分钟的平均负载
        double sysLoadAverage = os.getSystemLoadAverage();
        // 操作系统的版本
        String version = os.getVersion();
        System.out.println("操作系统的架构为：" + arch + "\n名称为：" + name + "\n版本为：" + version + "\n平均负载为：" + sysLoadAverage + "\nJVM可用处理器数量为：" + availableProcessors + "\n对象名为:" + os.getObjectName().toString());
        System.out.println();

        /**
         * 类加载器
         */
        System.out.println("*****************类加载器模块*****************");
        ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        // 已加载类的总数
        long loadedClassCount = classLoadingMXBean.getLoadedClassCount();
        // 加载类的总数
        long totalLoadedClassCount = classLoadingMXBean.getTotalLoadedClassCount();
        // 未加载类的总数
        long unloadedClassCount = classLoadingMXBean.getUnloadedClassCount();
        System.out.println("加载类的总数为：" + totalLoadedClassCount + "\n已加载类的总数为：" + loadedClassCount + "\n未加载类的总数为：" + unloadedClassCount + "\n对象名为:" + classLoadingMXBean.getObjectName().toString());
        System.out.println();

        /**
         * 编译器
         */
        System.out.println("*****************编译器模块*****************");
        CompilationMXBean compilationMXBean = ManagementFactory.getCompilationMXBean();
        // JIT编译器的名称
        String compilationName = compilationMXBean.getName();
        // 总的编译时间
        long compilationTime = compilationMXBean.getTotalCompilationTime();
        // JVM 是否支持编译器监控
        boolean isCompilationTimeMonitoringSupported = compilationMXBean.isCompilationTimeMonitoringSupported();
        System.out.println("编译器名称为：" + compilationName + "\n总的编译时间为：" + compilationTime + "\n是否支持编译时间监控：" + isCompilationTimeMonitoringSupported + "\n对象名为:" + compilationMXBean.getObjectName().toString());
        System.out.println();

        /**
         * 收集器
         */
        System.out.println("*****************收集器模块*****************");
        List<GarbageCollectorMXBean> garbageCollectorMXBeanList = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean garbageCollectorMXBean : garbageCollectorMXBeanList) {
            // 已经回收的次数
            long collectionCount = garbageCollectorMXBean.getCollectionCount();
            // 以毫秒为单位的近似累积回收时间
            long collectionTime = garbageCollectorMXBean.getCollectionTime();
            // 内存管理器管理的内存池的名称
            String[] memoryNames = garbageCollectorMXBean.getMemoryPoolNames();
            // 收集器的名称
            String garbageCollectorName = garbageCollectorMXBean.getName();
//            System.out.println("回收次数为：" + collectionCount + "\n累积回收时间为：" + collectionTime + "\n内存池名称为：" + JSON.toJSONString(memoryNames) + "\n收集器名称为：" + garbageCollectorName + "\n对象名为：" + garbageCollectorMXBean.getObjectName());
            System.out.println();
        }
        System.out.println();

        /**
         * 内存管理模块
         */
        System.out.println("*****************内存管理模块*****************");
        List<MemoryManagerMXBean> memoryManagerMXBeanList = ManagementFactory.getMemoryManagerMXBeans();
        for (MemoryManagerMXBean memoryManagerMXBean : memoryManagerMXBeanList) {
            // 内存管理器管理的内存池的名称
            String[] memoryPoolNames = memoryManagerMXBean.getMemoryPoolNames();
            // 当前的内存管理器
            String memoryManageName = memoryManagerMXBean.getName();
            // 内存管理器在JVM中是否有效
            boolean isValid = memoryManagerMXBean.isValid();
//            System.out.println("内存池名称为：" + JSON.toJSONString(memoryPoolNames) + "\n当前的内存管理器为：" + memoryManageName + "\n内存管理器在JVM中是否生效：" + isValid + "\n对象名为：" + memoryManagerMXBean.getObjectName().toString());
//            System.out.println();
        }

        /**
         * JVM内存管理的bean
         */
        System.out.println("*****************JVM内存管理的bean*****************");
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        // JVM用于对象分配的堆的当前内存使用情况
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        // JVM使用的非堆内存的当前内存使用情况
        MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        // 待回收的对象的大致数目
        long objectPendingFinalizationCount = memoryMXBean.getObjectPendingFinalizationCount();
        // 是否启用了内存系统的详细输出
        boolean isVerbose = memoryMXBean.isVerbose();
//        System.out.println("JVM用于对象分配的堆的当前内存使用情况：" + JSON.toJSONString(heapMemoryUsage) + "\nJVM使用的非堆内存的当前内存使用情况：" + JSON.toJSONString(nonHeapMemoryUsage) + "\n待回收的对象的大致数目：" + objectPendingFinalizationCount + "\n是否启用了内存系统的详细输出:" + isVerbose + "\n对象名为：" + memoryMXBean.getObjectName().toString());
        System.out.println();

        // 启用了内存系统的详细输出
        memoryMXBean.setVerbose(true);
//        System.out.println("启用了内存系统的详细输出之后：\nJVM用于对象分配的堆的当前内存使用情况：" + JSON.toJSONString(heapMemoryUsage) + "\nJVM使用的非堆内存的当前内存使用情况：" + JSON.toJSONString(nonHeapMemoryUsage) + "\n待回收的对象的大致数目：" + objectPendingFinalizationCount + "\n是否启用了内存系统的详细输出:" + memoryMXBean.isVerbose() + "\n对象名为：" + memoryMXBean.getObjectName().toString());
        System.out.println();

        // 运行垃圾收集
        memoryMXBean.gc();
//        System.out.println("启用了内存系统的详细输出之后，且运行垃圾收集之后：\nJVM用于对象分配的堆的当前内存使用情况：" + JSON.toJSONString(heapMemoryUsage) + "\nJVM使用的非堆内存的当前内存使用情况：" + JSON.toJSONString(nonHeapMemoryUsage) + "\n待回收的对象的大致数目：" + objectPendingFinalizationCount + "\n是否启用了内存系统的详细输出:" + memoryMXBean.isVerbose() + "\n对象名为：" + memoryMXBean.getObjectName().toString());
        System.out.println();

        /**
         * JVM中MemoryPoolMXBean对象的列表
         */
        System.out.println("*****************JVM内存池中MemoryPoolMXBean对象*****************");
        List<MemoryPoolMXBean> memoryPoolMXBeanList = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean memoryPoolMXBean : memoryPoolMXBeanList) {
            // JVM最近在回收此内存池中未使用对象之后的内存使用情况
//            MemoryUsage memoryUsage = memoryPoolMXBean.getCollectionUsage();
            // 此内存池的集合使用阈值（字节）
//            memoryPoolMXBean.setCollectionUsageThreshold(100000);
//            long collectionUsageThreshold = memoryPoolMXBean.getCollectionUsageThreshold();
            // JVM检测到内存使用率已达到或超过集合使用率阈值的次数
//            long collectionUsageThresholdCount = memoryPoolMXBean.getCollectionUsageThresholdCount();
            // 管理此内存池的内存管理器的名称。每个内存池至少由一个内存管理器管理。
            String[] memoryManagerNames = memoryPoolMXBean.getMemoryManagerNames();
            // 当前内存池名称
            String memoryPoolName = memoryPoolMXBean.getName();
            // 自Java虚拟机启动或峰值重设以来此内存池的峰值内存使用量
            MemoryUsage memoryUsage1 = memoryPoolMXBean.getPeakUsage();
            // 内存池类型
            MemoryType memoryType = memoryPoolMXBean.getType();
            // 对此内存池的内存使用情况的估计值
            MemoryUsage memoryUsage2 = memoryPoolMXBean.getUsage();
            // 此内存池的使用阈值（字节）。每个内存池都有一个依赖于平台的默认阈值。
//            long usageThreshold = memoryPoolMXBean.getUsageThreshold();
            // 内存使用量超过使用阈值的次数
//            long usageThresholdCount = memoryPoolMXBean.getUsageThresholdCount();
            // JVM在最近一次回收之后内存使用情况是否达到或者超过回收使用阈值
//            boolean isCollectionUsageThresholdExceeded = memoryPoolMXBean.isCollectionUsageThresholdExceeded();
            // 内存池知否支持回收使用阈值
            boolean isCollectionUsageThresholdSupported = memoryPoolMXBean.isCollectionUsageThresholdSupported();
            // 内存池的内存使用是否达到或超过其使用阈值
//            boolean isUsageThresholdExceeded = memoryPoolMXBean.isUsageThresholdExceeded();
            // 内存池是否支持使用阈值
            boolean isUsageThresholdSupported = memoryPoolMXBean.isUsageThresholdSupported();
            // JVM中内存池是否有效
            boolean isValid = memoryPoolMXBean.isValid();
            // + "\n获得此内存池的集合使用阈值（字节数）：" + collectionUsageThreshold +  "\nJVM检测到内存使用率已达到或超过集合使用率阈值的次数:" + collectionUsageThresholdCount
//            System.out.println("JVM最近在回收此内存池中未使用对象之后的内存使用情况:" + JSON.toJSONString(memoryUsage));
//            System.out.println("管理此内存池的内存管理器的名称:" + JSON.toJSONString(memoryManagerNames) + "\n当前内存池名称:" + memoryPoolName + "\n自Java虚拟机启动或峰值重设以来此内存池的峰值内存使用量:" + JSON.toJSONString(memoryUsage1));
            //  + "\n此内存池的使用阈值（字节数）：" + usageThreshold + "\n内存使用量超过使用阈值的次数:" + usageThresholdCount
//            System.out.println("内存池类型：" + JSON.toJSONString(memoryType) + "\n对此内存池的内存使用情况的估计值:" + JSON.toJSONString(memoryUsage2));
            // "JVM在最近一次回收之后内存使用情况是否达到或者超过回收使用阈值:" + isCollectionUsageThresholdExceeded + "\n内存池的内存使用是否达到或超过其使用阈值:" + isUsageThresholdExceeded +
            System.out.println("内存池知否支持回收使用阈值:" + isCollectionUsageThresholdSupported + "\n内存池是否支持使用阈值:" + isUsageThresholdSupported + "\nJVM中内存池是否有效:" + isValid);
            System.out.println("对象名为：" + memoryPoolMXBean.getObjectName().toString());
            System.out.println();
        }

//        System.out.println(JSON.toJSONString(ManagementFactory.getPlatformMBeanServer()));
        System.out.println("******************JVM在运行时管理的bean********************");
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        System.out.println("启动类路径：" + runtimeMXBean.getBootClassPath());
        System.out.println("类路径：" + runtimeMXBean.getClassPath());
        System.out.println("输入参数：" + runtimeMXBean.getInputArguments());
        System.out.println("库路径：" + runtimeMXBean.getLibraryPath());
        System.out.println("管理的具体版本：" + runtimeMXBean.getManagementSpecVersion());
        System.out.println("主机名称：" + runtimeMXBean.getName());
        System.out.println("JVM规范名称：" + runtimeMXBean.getSpecName());
        System.out.println("JVM规范供应商：" + runtimeMXBean.getSpecVendor());
        System.out.println("JVM规范版本：" + runtimeMXBean.getSpecVersion());
        System.out.println("启动时间：" + runtimeMXBean.getStartTime());
        System.out.println("系统属性：" + runtimeMXBean.getSystemProperties());
        System.out.println("JVM正常运行时间：" + runtimeMXBean.getUptime());
        System.out.println("JVM实现名称：" + runtimeMXBean.getVmName());
        System.out.println("JVM实现供应商：" + runtimeMXBean.getVmVendor());
        System.out.println("JVM实现版本：" + runtimeMXBean.getVmVersion());

        System.out.println("\n******************JVM线程系统管理的bean********************");
//        System.out.println(JSON.toJSONString(ManagementFactory.getThreadMXBean()));
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        Method[] methods = threadMXBean.getClass().getDeclaredMethods();
//        System.out.println("死锁线程为：" + JSON.toJSONString(threadMXBean.findDeadlockedThreads()));
//        System.out.println("监控死锁线程为：" + JSON.toJSONString(threadMXBean.findMonitorDeadlockedThreads()));
//        System.out.println("所有的线程id为：" + JSON.toJSONString(threadMXBean.getAllThreadIds()));
//        System.out.println("当前线程占用的CPU时间为：" + JSON.toJSONString(threadMXBean.getCurrentThreadCpuTime()));
//        System.out.println("当前线程在用户模式下执行的CPU时间（纳秒）为：" + JSON.toJSONString(threadMXBean.getCurrentThreadUserTime()));
//        System.out.println("当前的实时守护进程线程数为：" + JSON.toJSONString(threadMXBean.getDaemonThreadCount()));
//        System.out.println("自Java虚拟机启动或峰值重置后的活动线程计数峰值为：" + JSON.toJSONString(threadMXBean.getPeakThreadCount()));
//        System.out.println("当前的活动线程数，包括守护进程和非守护进程线程为：" + JSON.toJSONString(threadMXBean.getThreadCount()));
//        System.out.println("自Java虚拟机启动以来创建和启动的线程总数为：" + JSON.toJSONString(threadMXBean.getTotalStartedThreadCount()));
//        System.out.println("对象名称为：" + JSON.toJSONString(threadMXBean.getObjectName()));

        System.out.println("\n******************java平台所有的管理监控接口********************");
        Set<Class<? extends PlatformManagedObject>> classSet = ManagementFactory.getPlatformManagementInterfaces();
        for (Class platformManagedObject : classSet) {
//            System.out.println(JSON.toJSONString(platformManagedObject));
        }

    }
}
