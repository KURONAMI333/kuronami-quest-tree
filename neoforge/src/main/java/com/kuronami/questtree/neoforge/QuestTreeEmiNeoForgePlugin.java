package com.kuronami.questtree.neoforge;

import com.kuronami.questtree.client.compat.QuestTreeEmiPlugin;
import dev.emi.emi.api.EmiEntrypoint;

/** EMI's NeoForge annotation scanner finds this subclass; the Fabric entrypoint points at the parent. */
@EmiEntrypoint
public final class QuestTreeEmiNeoForgePlugin extends QuestTreeEmiPlugin {
}
