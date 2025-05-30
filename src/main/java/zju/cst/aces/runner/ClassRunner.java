package zju.cst.aces.runner;

import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.runner.solution_runner.ChatTesterRunner;
import zju.cst.aces.runner.solution_runner.HITSRunner;
import zju.cst.aces.runner.solution_runner.MUTAPRunner;
import zju.cst.aces.util.Counter;
import zju.cst.aces.util.TestClassMerger;
import zju.cst.aces.util.ClassNameProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class ClassRunner extends AbstractRunner {
    public ClassInfo classInfo;
    public File infoDir;
    public int index;

    public ClassRunner(Config config, String fullClassName) throws IOException {
        super(config, fullClassName);
        infoDir = config.getParseOutput().resolve(fullClassName.replace(".", File.separator)).toFile();
        if (!infoDir.isDirectory()) {
            config.getLogger().warn("Error: " + fullClassName + " no parsed info found");
        }
        File classInfoFile = new File(infoDir + File.separator + "class.json");
        classInfo = GSON.fromJson(new String(Files.readAllBytes(classInfoFile.toPath()), StandardCharsets.UTF_8), ClassInfo.class);
    }

    @Override
    public void start() throws IOException {
        if (config.isEnableMultithreading() == true) {
            methodJob();
        } else {
            for (String mSig : classInfo.methodSigs.keySet()) {
                MethodInfo methodInfo = getMethodInfo(config, classInfo, mSig);
                if (!Counter.filter(methodInfo)) {
                    config.getLogger().info("Skip method: " + mSig + " in class: " + fullClassName);
                    continue;
                }
//                new MethodRunner(config, fullClassName, methodInfo).start();
                selectRunner(config.getPhaseType(), fullClassName, methodInfo);
                int newCount = config.getCompletedJobCount().incrementAndGet();
                config.getLogger().info(String.format("\n==========================\n[%s] Completed Method Jobs:   [ %s /  %s]", config.pluginSign, newCount, config.getJobCount()));
            }
        }
        if (config.isEnableMerge()) {
            new TestClassMerger(config, fullClassName).mergeWithSuite();
        }
    }

    public void methodJob() {
        ExecutorService executor = Executors.newFixedThreadPool(config.getMethodThreads());
        List<Future<String>> futures = new ArrayList<>();
        for (String mSig : classInfo.methodSigs.keySet()) {
            Callable<String> callable = new Callable<String>() {
                @Override
                public String call() throws Exception {
                    MethodInfo methodInfo = getMethodInfo(config, classInfo, mSig);
                    if (methodInfo == null) {
                        return "No parsed info found for " + mSig + " in " + fullClassName;
                    }
                    if (!Counter.filter(methodInfo)) {
                        return "Skip method: " + mSig + " in class: " + fullClassName;
                    }
//                    new MethodRunner(config, fullClassName, methodInfo).start();
                    selectRunner(config.getPhaseType(), fullClassName, methodInfo);
                    int newCount = config.getCompletedJobCount().incrementAndGet();
                    config.getLogger().info(String.format("\n==========================\n[%s] Completed Method Jobs:   [ %s /  %s]", config.pluginSign, newCount, config.getJobCount()));
                    return "Processed " + mSig;
                }
            };
            Future<String> future = executor.submit(callable);
            futures.add(future);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                executor.shutdownNow();
            }
        });

        for (Future<String> future : futures) {
            try {
                String result = future.get();
                System.out.println(result);
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
    }

    public void selectRunner(String runnerType, String fullClassName, MethodInfo methodInfo) throws IOException {
        // Map templateName to a specific PromptFile enum constant
        switch (runnerType) {
            case "CHATTESTER":
                new ChatTesterRunner(config, fullClassName, methodInfo).start();
                break;
            case "HITS":
//                config.getLogger().warn("HITS will be ignored!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                new HITSRunner(config, fullClassName, methodInfo).start();
                break;
            case "MUTAP":
                new MUTAPRunner(config, fullClassName, methodInfo).start();
                break;
            default:
                new MethodRunner(config, fullClassName, methodInfo).start();
                break;
        }
    }
}
