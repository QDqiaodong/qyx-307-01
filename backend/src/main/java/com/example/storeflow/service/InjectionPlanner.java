package com.example.storeflow.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 回灌分配器：把要疏散的人员/客流，按各承接区域当时的剩余额度拆成整数，
 * 要求总量被完整吃下，且任何一块承接区域都不超过其剩余容量/编制。
 *
 * <p>纯函数式、无副作用，便于在“先试算可行性、通过后才真正改账”的事务里复用。
 * 分配规则：先按剩余额度比例取整（floor），再用最大余数法把余量依次分给小数余数更大、
 * 且仍有硬余量的区域，全程用硬性剩余上限裁剪，因此任何承接区域都不会超额。
 */
public final class InjectionPlanner {

    private InjectionPlanner() {
    }

    /**
     * 按上限把 total 拆给若干承接区域。
     *
     * @param total 要疏散的总量（人员或客流），&ge;0
     * @param caps  与目标区域一一对应的剩余上限
     * @return 与 caps 等长、等顺序的分配结果
     * @throws FeasibilityException 当所有上限之和 &lt; total（承接不下）
     */
    public static List<Integer> splitByCapacity(int total, List<Integer> caps) {
        int sumCap = caps.stream().mapToInt(Integer::intValue).sum();
        if (sumCap < total) {
            throw new FeasibilityException(
                    "回灌总量 " + total + " 超过所有承接区域剩余额度之和 " + sumCap);
        }

        int n = caps.size();
        List<Integer> alloc = new ArrayList<>(n);
        for (int c : caps) {
            alloc.add(0);
        }
        if (total <= 0 || n == 0) {
            return alloc;
        }

        // 第一轮：按比例 floor。权重 = 剩余额度 / 总剩余额度。
        double[] frac = new double[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            double exact = (double) total * caps.get(i) / sumCap;
            long f = (long) Math.floor(exact);
            // 单块 floor 也绝不能超过它自己的硬上限
            f = Math.min(f, caps.get(i));
            frac[i] = exact - f; // 始终是 [0,1) 的小数部分，不随后续分配改变
            alloc.set(i, (int) f);
            assigned += f;
        }

        long leftToAssign = total - assigned;

        // 第二轮：最大余数法。每个余量单位都分给“小数余数最大、且仍有硬余量”的区域；
        // 余数相同则剩余硬余量更大、下标更小者优先，结果确定可复现。
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        int guard = 0;
        while (leftToAssign > 0) {
            order.sort(Comparator
                    .comparingDouble((Integer i) -> -frac[i])
                    .thenComparing(i -> -(caps.get(i) - alloc.get(i)))
                    .thenComparingInt(i -> i));
            boolean placed = false;
            for (int i : order) {
                if (alloc.get(i) < caps.get(i)) {
                    alloc.set(i, alloc.get(i) + 1);
                    leftToAssign--;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                // 理论上不可达：sumCap >= total 已保证总能放下，这里只是防御性断言
                throw new FeasibilityException("回灌分配出现无法放置的余量: " + leftToAssign);
            }
            if (++guard > total + n + 1) {
                throw new FeasibilityException("回灌分配迭代异常");
            }
        }

        return alloc;
    }

    /** 承接不下（剩余额度之和不足）。 */
    public static class FeasibilityException extends RuntimeException {
        public FeasibilityException(String message) {
            super(message);
        }
    }
}
