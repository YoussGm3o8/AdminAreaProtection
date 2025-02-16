package adminarea.test.benchmark;

import adminarea.test.MockPlugin;
import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
public class AreaBenchmark {
    private AdminAreaProtectionPlugin plugin;
    private Area testArea;

    @Setup
    public void setup() {
        plugin = new MockPlugin(); // Create test plugin instance
        testArea = Area.builder()
            .name("BenchmarkArea")
            .world("world")
            .coordinates(0, 1000, 0, 255, 0, 1000)
            .priority(1)
            .build();
        plugin.addArea(testArea);
    }

    @Benchmark
    public void benchmarkAreaLookup() {
        plugin.getHighestPriorityArea("world", 500, 100, 500);
    }

    @Benchmark
    public void benchmarkAreaContainment() {
        for (int i = 0; i < 1000; i++) {
            testArea.isInside("world", i, 100, i);
        }
    }

    @Benchmark
    public void benchmarkPermissionCheck() {
        for (int i = 0; i < 100; i++) {
            testArea.getGroupPermission("default", "build");
            testArea.getGroupPermission("vip", "interact");
            testArea.getGroupPermission("admin", "manage");
        }
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(AreaBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
