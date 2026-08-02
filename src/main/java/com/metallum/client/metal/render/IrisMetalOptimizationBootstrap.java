package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reflective bridge that keeps the experimental planner decoupled from private post-chain node types. */
public final class IrisMetalOptimizationBootstrap {
    private IrisMetalOptimizationBootstrap() {
    }

    public static void onPostChainCreated(final Object chain) {
        if (chain == null) return;
        try {
            List<IrisMetalExperimentalOptimizer.PassDescriptor> passes = new ArrayList<>();
            List<IrisMetalExperimentalOptimizer.ProgramDescriptor> programs = new ArrayList<>();
            Set<String> persistent = new LinkedHashSet<>();
            Set<String> known = new LinkedHashSet<>();

            int targetCount = intField(chain, "targetCount");
            for (int index = 0; index < targetCount; index++) known.add("colortex" + index);
            known.add("depthtex0");
            known.add("depthtex1");
            known.add("depthtex2");

            Object stages = field(chain, "passes");
            if (stages instanceof Map<?, ?> map) {
                for (Object stagePasses : map.values()) {
                    if (!(stagePasses instanceof Collection<?> collection)) continue;
                    for (Object planned : collection) addRasterPass(planned, passes, programs);
                }
            }

            Object computeGroups = field(chain, "computeGroups");
            if (computeGroups instanceof Map<?, ?> map) {
                for (Object groups : map.values()) {
                    if (!(groups instanceof Collection<?> collection)) continue;
                    for (Object group : collection) {
                        Object computes = invoke(group, "computes");
                        if (computes instanceof Collection<?> computeCollection) {
                            for (Object compute : computeCollection) addComputePass(compute, passes, programs);
                        }
                    }
                }
            }
            addComputeCollection(field(chain, "setupComputes"), passes, programs);
            addComputeCollection(field(chain, "finalComputes"), passes, programs);

            Object finalPass = field(chain, "finalPass");
            if (finalPass != null) addFinalPass(finalPass, passes, programs);

            Object histories = field(chain, "finalHistoryTargets");
            if (histories instanceof Collection<?> collection) {
                for (Object value : collection) {
                    if (value instanceof Number number) persistent.add("colortex" + number.intValue());
                }
            }
            persistent.add("colortex0");

            IrisMetalExperimentalOptimizer.build(passes, programs, List.of(), persistent, known);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            IrisMetalExperimentalOptimizer.clear();
            Metallum.LOGGER.warn("[metallum-iris] advanced optimization plan construction failed; conservative path retained", failure);
        }
    }

    public static void onPostChainClosed() {
        IrisMetalExperimentalOptimizer.clear();
    }

    public static boolean depthtex1Required() {
        IrisMetalOptimizationPlan plan = IrisMetalExperimentalOptimizer.active();
        return plan == null || plan.resourceLiveness().depthtex1Required();
    }

    public static boolean depthtex2Required() {
        IrisMetalOptimizationPlan plan = IrisMetalExperimentalOptimizer.active();
        return plan == null || plan.resourceLiveness().depthtex2Required();
    }

    public static String activePlanJson() {
        IrisMetalOptimizationPlan plan = IrisMetalExperimentalOptimizer.active();
        return plan == null ? "{}\n" : IrisMetalExperimentalOptimizer.toJson(plan);
    }

    private static void addRasterPass(
            final Object planned,
            final List<IrisMetalExperimentalOptimizer.PassDescriptor> passes,
            final List<IrisMetalExperimentalOptimizer.ProgramDescriptor> programs
    ) throws ReflectiveOperationException {
        Object info = field(planned, "info");
        Object program = field(planned, "program");
        String name = String.valueOf(invoke(info, "name"));
        int[] drawBuffers = (int[]) invoke(info, "drawBuffers");
        BitSet readsFromAlt = (BitSet) invoke(info, "readsFromAlt");
        Set<String> samplers = stringSet(invoke(info, "declaredSamplers"));
        List<IrisMetalHazardGraph.ResourceUse> uses = new ArrayList<>();
        for (String sampler : samplers) addSamplerUse(uses, sampler);
        for (int target : drawBuffers) {
            uses.add(new IrisMetalHazardGraph.ResourceUse(
                    "colortex" + target,
                    IrisMetalHazardGraph.Access.ATTACHMENT_WRITE
            ));
        }
        List<IrisMetalOptimizationPlan.AttachmentPolicy> attachments = new ArrayList<>();
        for (int target : drawBuffers) {
            attachments.add(new IrisMetalOptimizationPlan.AttachmentPolicy(
                    "colortex" + target,
                    readsFromAlt.get(target)
                            ? IrisMetalOptimizationPlan.LoadAction.LOAD
                            : IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                    IrisMetalOptimizationPlan.StoreAction.STORE
            ));
        }
        String key = java.util.Arrays.toString(drawBuffers);
        passes.add(new IrisMetalExperimentalOptimizer.PassDescriptor(
                name,
                IrisMetalExperimentalOptimizer.PassDescriptor.Kind.RENDER,
                uses,
                false,
                attachments,
                key
        ));
        addProgramDescriptor("render/" + name, program, programs);
    }

    private static void addFinalPass(
            final Object finalPass,
            final List<IrisMetalExperimentalOptimizer.PassDescriptor> passes,
            final List<IrisMetalExperimentalOptimizer.ProgramDescriptor> programs
    ) throws ReflectiveOperationException {
        Object info = invoke(finalPass, "info");
        Object program = field(finalPass, "program");
        String name = String.valueOf(invoke(info, "name"));
        Set<String> samplers = stringSet(invoke(info, "declaredSamplers"));
        List<IrisMetalHazardGraph.ResourceUse> uses = new ArrayList<>();
        for (String sampler : samplers) addSamplerUse(uses, sampler);
        uses.add(new IrisMetalHazardGraph.ResourceUse("mainTarget", IrisMetalHazardGraph.Access.ATTACHMENT_WRITE));
        passes.add(new IrisMetalExperimentalOptimizer.PassDescriptor(
                name,
                IrisMetalExperimentalOptimizer.PassDescriptor.Kind.RENDER,
                uses,
                false,
                List.of(new IrisMetalOptimizationPlan.AttachmentPolicy(
                        "mainTarget",
                        IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                        IrisMetalOptimizationPlan.StoreAction.STORE
                )),
                "mainTarget"
        ));
        addProgramDescriptor("final/" + name, program, programs);
    }

    private static void addComputeCollection(
            final Object value,
            final List<IrisMetalExperimentalOptimizer.PassDescriptor> passes,
            final List<IrisMetalExperimentalOptimizer.ProgramDescriptor> programs
    ) throws ReflectiveOperationException {
        if (!(value instanceof Collection<?> collection)) return;
        for (Object compute : collection) addComputePass(compute, passes, programs);
    }

    private static void addComputePass(
            final Object compute,
            final List<IrisMetalExperimentalOptimizer.PassDescriptor> passes,
            final List<IrisMetalExperimentalOptimizer.ProgramDescriptor> programs
    ) throws ReflectiveOperationException {
        Object info = field(compute, "info");
        Object reflection = field(compute, "reflection");
        String name = String.valueOf(invoke(info, "name"));
        List<IrisMetalHazardGraph.ResourceUse> uses = new ArrayList<>();
        List<IrisMetalOptimizationPlan.ArgumentSlot> slots = new ArrayList<>();
        Object resources = invoke(reflection, "resources");
        if (resources instanceof Collection<?> collection) {
            for (Object resource : collection) {
                String resourceName = String.valueOf(invoke(resource, "name"));
                String kind = String.valueOf(invoke(resource, "kind"));
                int binding = ((Number) invoke(resource, "binding")).intValue();
                boolean writable = kind.contains("STORAGE");
                IrisMetalOptimizationPlan.ArgumentSlot.Kind slotKind = kind.contains("BUFFER")
                        ? IrisMetalOptimizationPlan.ArgumentSlot.Kind.BUFFER
                        : kind.contains("SAMPLER")
                        ? IrisMetalOptimizationPlan.ArgumentSlot.Kind.SAMPLER
                        : IrisMetalOptimizationPlan.ArgumentSlot.Kind.TEXTURE;
                slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(resourceName, slotKind, binding, writable));
                String stable = stableResourceName(resourceName);
                uses.add(new IrisMetalHazardGraph.ResourceUse(
                        stable,
                        writable ? IrisMetalHazardGraph.Access.STORAGE_WRITE : IrisMetalHazardGraph.Access.SAMPLED_READ
                ));
            }
        }
        passes.add(new IrisMetalExperimentalOptimizer.PassDescriptor(
                name,
                IrisMetalExperimentalOptimizer.PassDescriptor.Kind.COMPUTE,
                uses,
                true,
                List.of(),
                ""
        ));
        programs.add(new IrisMetalExperimentalOptimizer.ProgramDescriptor("compute/" + name, slots));
    }

    private static void addProgramDescriptor(
            final String key,
            final Object program,
            final List<IrisMetalExperimentalOptimizer.ProgramDescriptor> programs
    ) throws ReflectiveOperationException {
        List<IrisMetalOptimizationPlan.ArgumentSlot> slots = new ArrayList<>();
        Object samplers = invoke(program, "samplers");
        if (samplers instanceof Collection<?> collection) {
            int index = 0;
            for (Object sampler : collection) {
                slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(
                        String.valueOf(invoke(sampler, "name")),
                        IrisMetalOptimizationPlan.ArgumentSlot.Kind.TEXTURE,
                        index++,
                        false
                ));
            }
        }
        programs.add(new IrisMetalExperimentalOptimizer.ProgramDescriptor(key, slots));
    }

    private static void addSamplerUse(
            final List<IrisMetalHazardGraph.ResourceUse> uses,
            final String sampler
    ) {
        uses.add(new IrisMetalHazardGraph.ResourceUse(
                stableResourceName(sampler),
                IrisMetalHazardGraph.Access.SAMPLED_READ
        ));
    }

    private static String stableResourceName(final String name) {
        if (name.matches("colortex\\d+")) return name;
        if (name.matches("colorimg\\d+")) return "colortex" + name.substring("colorimg".length());
        return switch (name) {
            case "gcolor" -> "colortex0";
            case "gdepth" -> "colortex1";
            case "gnormal" -> "colortex2";
            case "composite" -> "colortex3";
            case "gaux1" -> "colortex4";
            case "gaux2" -> "colortex5";
            case "gaux3" -> "colortex6";
            case "gaux4" -> "colortex7";
            default -> name;
        };
    }

    private static Set<String> stringSet(final Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) result.add(String.valueOf(item));
        }
        return result;
    }

    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int intField(final Object target, final String name) throws ReflectiveOperationException {
        return ((Number) field(target, name)).intValue();
    }

    private static Object invoke(final Object target, final String name) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Field findField(Class<?> type, final String name) throws NoSuchFieldException {
        while (type != null) {
            try { return type.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, final String name) throws NoSuchMethodException {
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) return method;
            }
            type = type.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }
}
