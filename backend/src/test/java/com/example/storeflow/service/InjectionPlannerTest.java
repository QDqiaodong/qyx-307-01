package com.example.storeflow.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InjectionPlannerTest {

    @Test
    void splitsExactTotalAndNeverExceedsCaps() {
        List<Integer> caps = Arrays.asList(50, 30, 20);
        List<Integer> plan = InjectionPlanner.splitByCapacity(100, caps);

        assertEquals(3, plan.size());
        assertEquals(100, plan.stream().mapToInt(Integer::intValue).sum());
        for (int i = 0; i < caps.size(); i++) {
            assertTrue(plan.get(i) <= caps.get(i),
                    "承接区域 " + i + " 分到 " + plan.get(i) + " 超过剩余上限 " + caps.get(i));
            assertTrue(plan.get(i) >= 0);
        }
    }

    @Test
    void distributesRoughlyProportionalToRemaining() {
        // 80 余量 ：20 余量 => 应大致 4:1
        List<Integer> plan = InjectionPlanner.splitByCapacity(100, Arrays.asList(80, 20));
        assertEquals(100, plan.get(0) + plan.get(1));
        assertTrue(plan.get(0) <= 80 && plan.get(1) <= 20);
        // 比例分配：80 / 20
        assertEquals(80, plan.get(0));
        assertEquals(20, plan.get(1));
    }

    @Test
    void handlesZeroEvacuees() {
        List<Integer> plan = InjectionPlanner.splitByCapacity(0, Arrays.asList(50, 50));
        assertEquals(Arrays.asList(0, 0), plan);
    }

    @Test
    void singleReceiverWithinCap() {
        List<Integer> plan = InjectionPlanner.splitByCapacity(40, List.of(100));
        assertEquals(List.of(40), plan);
    }

    @Test
    void throwsWhenNoReceiverCanHoldTotal() {
        // 总剩余 30 < 需求 100
        assertThrows(InjectionPlanner.FeasibilityException.class,
                () -> InjectionPlanner.splitByCapacity(100, Arrays.asList(10, 20)));
    }

    @Test
    void largestRemainderFillsAllUnitsWithinCaps() {
        // caps 总和恰好等于需求，每块都必须被填满
        List<Integer> plan = InjectionPlanner.splitByCapacity(37, Arrays.asList(10, 12, 15));
        assertEquals(Arrays.asList(10, 12, 15), plan);
    }
}
