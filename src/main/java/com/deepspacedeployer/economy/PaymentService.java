package com.deepspacedeployer.economy;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.item.CurrencyItem;
import com.deepspacedeployer.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 统一支付：<b>实体公司币 → 公司账户 → 通用账户</b>。
 *
 * <p>规则来自 P0-3：</p>
 * <ol>
 *   <li>先花付款公司自己的实体券；</li>
 *   <li>实体券不够全款时，先花光手里的实体券，再用公司账户补；</li>
 *   <li>公司账户也不够，再用通用账户补；</li>
 *   <li>实体券够全款时，按 1 元券优先，再按 5 → 10 → 20 → 50 → 100
 *       从小到大拿整张；最后一张超过价格就“打散”，<b>找零只给 1 元券</b>；</li>
 *   <li>只收付款公司自己的券，别家公司的券不参与。</li>
 * </ol>
 *
 * <p>账户/通用补足的部分不产生找零；只有实体券超额时才找零。</p>
 */
public final class PaymentService {

    /**
     * @param cashUsed    实际用掉的实体券面值（找零后净值）
     * @param accountUsed 公司账户补的部分
     * @param genericUsed 通用账户补的部分
     * @param change      找零的 1 元券数量
     */
    public record Result(long cashUsed, long accountUsed, long genericUsed, long change) {
    }

    private static final CurrencyDenomination[] DENOMINATIONS = CurrencyDenomination.VALUES;

    /** 实体券 + 公司账户 + 通用账户，任意组合够全款即可。 */
    public static boolean canPay(ServerPlayer player, UUID account, Company company, long amount) {
        if (amount <= 0L) {
            return true;
        }
        CompanyFunds funds = CompanyFunds.get(player.server);
        long accountBalance = account == null ? 0L : funds.balance(account, company);
        long generic = account == null ? 0L : funds.generic(account);
        return cashTotal(player, company) + accountBalance + generic >= amount;
    }

    /**
     * 付款：实体券 → 公司账户 → 通用账户。
     *
     * @return null=三边加起来都不够；否则返回支付明细
     */
    public static Result pay(ServerPlayer player, UUID account, Company company, long amount) {
        if (amount <= 0L) {
            return new Result(0L, 0L, 0L, 0L);
        }
        CompanyFunds funds = CompanyFunds.get(player.server);
        long[] available = cashCounts(player, company);
        long cashTotal = cashTotal(available);
        long accountBalance = account == null ? 0L : funds.balance(account, company);
        long genericBalance = account == null ? 0L : funds.generic(account);

        // 1) 实体券够全款：只用实体券，按 1 元优先 + 打散 + 只找 1 元
        if (cashTotal >= amount) {
            long[] plan = buildCashPlan(available, amount);
            if (plan == null) {
                return null;
            }
            long change = plan[DENOMINATIONS.length] ;
            for (int i = 0; i < DENOMINATIONS.length; i++) {
                if (plan[i] > 0L && !remove(player, company, DENOMINATIONS[i], plan[i])) {
                    return null;
                }
            }
            giveCash(player, company, change);
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.cash_paid", amount, change)
                    .withStyle(ChatFormatting.GREEN), true);
            return new Result(amount, 0L, 0L, change);
        }

        // 2) 实体券不够：先花光实体券，再用公司账户，最后通用账户
        long remaining = amount - cashTotal;
        long accountUsed = Math.min(accountBalance, remaining);
        remaining -= accountUsed;
        long genericUsed = Math.min(genericBalance, remaining);
        remaining -= genericUsed;
        if (remaining > 0L) {
            return null;
        }

        removeAllCash(player, company);
        if (accountUsed > 0L) {
            funds.add(account, company, -accountUsed);
        }
        if (genericUsed > 0L) {
            funds.addGeneric(account, -genericUsed);
        }
        long supplement = accountUsed + genericUsed;
        if (cashTotal > 0L && supplement > 0L) {
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.cash_paid_mixed", cashTotal, supplement)
                    .withStyle(ChatFormatting.GREEN), true);
        }
        return new Result(cashTotal, accountUsed, genericUsed, 0L);
    }

    /** 退款：按支付明细原路退回（实体券退 1 元券，账户/通用回账户）。 */
    public static void refund(ServerPlayer player, UUID account, Company company, Result result) {
        if (result == null) {
            return;
        }
        CompanyFunds funds = CompanyFunds.get(player.server);
        if (result.accountUsed() > 0L) {
            funds.add(account, company, result.accountUsed());
        }
        if (result.genericUsed() > 0L) {
            funds.addGeneric(account, result.genericUsed());
        }
        if (result.cashUsed() > 0L) {
            giveCash(player, company, result.cashUsed());
        }
    }

    /** 给实体币（退款也用），按 1 元券发；单叠最多 100。 */
    public static void giveCash(ServerPlayer player, Company company, long amount) {
        if (amount <= 0L) {
            return;
        }
        Inventory inventory = player.getInventory();
        long left = amount;
        while (left > 0L) {
            int count = (int) Math.min(100L, left);
            ItemStack stack = new ItemStack(
                    ModItems.currencyItem(company, CurrencyDenomination.ONE), count);
            if (!inventory.add(stack)) {
                player.drop(stack, false);
            }
            left -= count;
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 生成实体券支付计划：1 元优先，再 5/10/20/50/100 从小到大；
     * 最后一张超过价格就找零。返回长度 7：前 6 个是各面额张数，最后一个是找零。
     */
    private static long[] buildCashPlan(long[] available, long amount) {
        long[] plan = new long[DENOMINATIONS.length + 1];
        long need = amount;
        long change = 0L;

        long ones = Math.min(available[0], need);
        plan[0] = ones;
        need -= ones;

        for (int i = 1; i < DENOMINATIONS.length && need > 0L; i++) {
            long value = DENOMINATIONS[i].value();
            while (need > 0L && available[i] > 0L) {
                if (need >= value) {
                    long take = Math.min(available[i], need / value);
                    plan[i] += take;
                    need -= take * value;
                    available[i] -= take;
                } else {
                    plan[i] += 1L;
                    change = value - need;
                    need = 0L;
                }
            }
        }
        if (need > 0L) {
            return null;
        }
        plan[DENOMINATIONS.length] = change;
        return plan;
    }

    private static long cashTotal(ServerPlayer player, Company company) {
        return cashTotal(cashCounts(player, company));
    }

    private static long cashTotal(long[] counts) {
        long total = 0L;
        for (int i = 0; i < counts.length; i++) {
            total += counts[i] * DENOMINATIONS[i].value();
        }
        return total;
    }

    private static long[] cashCounts(ServerPlayer player, Company company) {
        long[] counts = new long[DENOMINATIONS.length];
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            CurrencyItem currency = ModItems.currencyOf(inventory.getItem(i));
            if (currency == null || currency.company() != company) {
                continue;
            }
            counts[currency.denomination().ordinal()] += inventory.getItem(i).getCount();
        }
        return counts;
    }

    private static boolean remove(ServerPlayer player, Company company,
                                  CurrencyDenomination denomination, long count) {
        Inventory inventory = player.getInventory();
        long left = count;
        for (int i = 0; i < inventory.getContainerSize() && left > 0L; i++) {
            ItemStack stack = inventory.getItem(i);
            CurrencyItem currency = ModItems.currencyOf(stack);
            if (currency == null || currency.company() != company
                    || currency.denomination() != denomination) {
                continue;
            }
            int take = (int) Math.min(left, stack.getCount());
            inventory.removeItem(i, take);
            left -= take;
        }
        return left <= 0L;
    }

    private static void removeAllCash(ServerPlayer player, Company company) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            CurrencyItem currency = ModItems.currencyOf(stack);
            if (currency != null && currency.company() == company) {
                inventory.removeItem(i, stack.getCount());
            }
        }
    }

    private PaymentService() {
    }
}
