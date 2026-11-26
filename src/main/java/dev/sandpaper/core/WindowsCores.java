package dev.sandpaper.core;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
final class WindowsCores implements RenderCoreGuard.Cores {
    private static final int OFFSET_SIZE = 0;
    private static final int OFFSET_TYPE = 4;
    private static final int OFFSET_ID = 8;
    private static final int OFFSET_GROUP = 12;
    private static final int OFFSET_LOGICAL = 14;
    private static final int OFFSET_CORE = 15;
    private static final int MIN_ENTRY_BYTES = 16;
    private static final long STRUCT_ALIGNMENT = 8L;
    private final MethodHandle currentProcessor;
    private final MethodHandle currentThread;
    private final MethodHandle currentProcess;
    private final MethodHandle setSelectedCpuSets;
    private final MethodHandle getProcessDefaultCpuSets;
    private final int[] coreOfProcessor;
    private final int[] cpuSetIdOfProcessor;
    WindowsCores() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        currentProcessor = linker.downcallHandle(kernel32.find("GetCurrentProcessorNumber").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        currentThread = linker.downcallHandle(kernel32.find("GetCurrentThread").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        currentProcess = linker.downcallHandle(kernel32.find("GetCurrentProcess").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        setSelectedCpuSets = linker.downcallHandle(kernel32.find("SetThreadSelectedCpuSets").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT));
        getProcessDefaultCpuSets = linker.downcallHandle(kernel32.find("GetProcessDefaultCpuSets").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        MethodHandle getInformation = linker.downcallHandle(kernel32.find("GetSystemCpuSetInformation").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment process = (MemorySegment) currentProcess.invokeExact();
            MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
            int ignored = (int) getInformation.invokeExact(MemorySegment.NULL, 0, length, process, 0);
            int bytes = length.get(ValueLayout.JAVA_INT, 0);
            if (bytes < MIN_ENTRY_BYTES) {
                throw new IllegalStateException("no CPU set information (" + bytes + " bytes)");
            }
            MemorySegment buffer = arena.allocate(bytes, STRUCT_ALIGNMENT);
            int ok = (int) getInformation.invokeExact(buffer, bytes, length, process, 0);
            if (ok == 0) {
                throw new IllegalStateException("GetSystemCpuSetInformation failed");
            }
            int used = length.get(ValueLayout.JAVA_INT, 0);
            if (used < MIN_ENTRY_BYTES || used > bytes) {
                throw new IllegalStateException("CPU set information of " + used + " bytes in a buffer of " + bytes);
            }
            int highest = -1;
            for (int at = 0; at + MIN_ENTRY_BYTES <= used; ) {
                int size = buffer.get(ValueLayout.JAVA_INT, at + OFFSET_SIZE);
                if (size < MIN_ENTRY_BYTES || size > used - at) {
                    throw new IllegalStateException("bad CPU set entry size " + size);
                }
                if (buffer.get(ValueLayout.JAVA_INT, at + OFFSET_TYPE) == 0) {
                    if (buffer.get(ValueLayout.JAVA_SHORT, at + OFFSET_GROUP) != 0) {
                        throw new IllegalStateException("more than one processor group");
                    }
                    highest = Math.max(highest, buffer.get(ValueLayout.JAVA_BYTE, at + OFFSET_LOGICAL) & 0xFF);
                }
                at += size;
            }
            if (highest < 0) {
                throw new IllegalStateException("no logical processors in the CPU set information");
            }
            coreOfProcessor = new int[highest + 1];
            cpuSetIdOfProcessor = new int[highest + 1];
            java.util.Arrays.fill(coreOfProcessor, -1);
            for (int at = 0; at + MIN_ENTRY_BYTES <= used; ) {
                int size = buffer.get(ValueLayout.JAVA_INT, at + OFFSET_SIZE);
                if (buffer.get(ValueLayout.JAVA_INT, at + OFFSET_TYPE) == 0) {
                    int logical = buffer.get(ValueLayout.JAVA_BYTE, at + OFFSET_LOGICAL) & 0xFF;
                    coreOfProcessor[logical] = buffer.get(ValueLayout.JAVA_BYTE, at + OFFSET_CORE) & 0xFF;
                    cpuSetIdOfProcessor[logical] = buffer.get(ValueLayout.JAVA_INT, at + OFFSET_ID);
                }
                at += size;
            }
            for (int core : coreOfProcessor) {
                if (core < 0) {
                    throw new IllegalStateException("a logical processor has no CPU set entry");
                }
            }
        }
    }
    @Override
    public int currentProcessor() {
        try {
            return (int) currentProcessor.invokeExact();
        } catch (Throwable failed) {
            return -1;
        }
    }
    int cpuSetIdOf(int processor) {
        return cpuSetIdOfProcessor[processor];
    }
    @Override
    public int[] coreOfProcessor() {
        return coreOfProcessor.clone();
    }
    private int[] processDefaultIds(Arena arena, MemorySegment process) throws Throwable {
        MemorySegment required = arena.allocate(ValueLayout.JAVA_INT);
        for (int attempt = 0; attempt < 3; attempt++) {
            required.set(ValueLayout.JAVA_INT, 0, 0);
            int first = (int) getProcessDefaultCpuSets.invokeExact(process, MemorySegment.NULL, 0, required);
            int count = required.get(ValueLayout.JAVA_INT, 0);
            if (count < 0) {
                throw new IllegalStateException("negative CPU set count " + count);
            }
            if (count == 0) {
                if (first != 0) {
                    return null;
                }
                throw new IllegalStateException("GetProcessDefaultCpuSets failed");
            }
            MemorySegment ids = arena.allocate(4L * count, 4L);
            int ok = (int) getProcessDefaultCpuSets.invokeExact(process, ids, count, required);
            int written = required.get(ValueLayout.JAVA_INT, 0);
            if (ok == 0) {
                if (written > count) {
                    continue;
                }
                throw new IllegalStateException("GetProcessDefaultCpuSets failed");
            }
            if (written <= 0) {
                return null;
            }
            int[] out = new int[Math.min(count, written)];
            for (int i = 0; i < out.length; i++) {
                out[i] = ids.getAtIndex(ValueLayout.JAVA_INT, i);
            }
            return out;
        }
        throw new IllegalStateException("the process default CPU set kept changing");
    }
    private static boolean contains(int[] ids, int id) {
        for (int candidate : ids) {
            if (candidate == id) {
                return true;
            }
        }
        return false;
    }
    @Override
    public boolean restrictCurrentThread(int[] allowedProcessors) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment thread = (MemorySegment) currentThread.invokeExact();
            if (allowedProcessors == null) {
                return (int) setSelectedCpuSets.invokeExact(thread, MemorySegment.NULL, 0) != 0;
            }
            MemorySegment process = (MemorySegment) currentProcess.invokeExact();
            int[] inside = processDefaultIds(arena, process);
            int[] wanted = new int[allowedProcessors.length];
            int count = 0;
            for (int processor : allowedProcessors) {
                int id = cpuSetIdOfProcessor[processor];
                if (inside == null || contains(inside, id)) {
                    wanted[count++] = id;
                }
            }
            if (count == 0) {
                return (int) setSelectedCpuSets.invokeExact(thread, MemorySegment.NULL, 0) != 0;
            }
            MemorySegment ids = arena.allocate(4L * count, 4L);
            for (int i = 0; i < count; i++) {
                ids.setAtIndex(ValueLayout.JAVA_INT, i, wanted[i]);
            }
            return (int) setSelectedCpuSets.invokeExact(thread, ids, count) != 0;
        } catch (Throwable failed) {
            return false;
        }
    }
}
