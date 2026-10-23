package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.net.ModuleListPayload;

import java.util.ArrayList;
import java.util.List;

/** 客户端缓存的模块打印机预设（服务端打开模块打印机时发过来）。 */
public final class ModulePresetClient {

    private static List<ModuleListPayload.ModuleEntry> modules = List.of();
    private static int version;

    public static void accept(ModuleListPayload payload) {
        modules = payload.modules() == null ? List.of() : List.copyOf(payload.modules());
        version++;
    }

    public static List<ModuleListPayload.ModuleEntry> all() {
        return modules;
    }

    public static List<ModuleListPayload.ModuleEntry> visible(Company company, Tier tier) {
        List<ModuleListPayload.ModuleEntry> out = new ArrayList<>();
        for (ModuleListPayload.ModuleEntry entry : modules) {
            if (entry.tier() > tier.rank()) {
                continue;
            }
            if (entry.company() != null && !entry.company().isEmpty()
                    && !entry.company().equals(company.id())) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    public static int version() {
        return version;
    }

    public static void clear() {
        modules = List.of();
        version++;
    }

    private ModulePresetClient() {
    }
}
