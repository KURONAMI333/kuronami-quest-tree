package com.kuronami.questtree.network;

import com.kuronami.questtree.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Server to client: what a crate just gave, so the opening screen can show the same icons FTB Quests shows. The table
 * id lets that screen read the chance each icon had from the crate's own table.
 */
public record CrateLootPayload(List<ItemStack> items, String tableId) implements CustomPacketPayload {

    public static final Type<CrateLootPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "crate_loot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CrateLootPayload> STREAM_CODEC =
            StreamCodec.composite(ItemStack.OPTIONAL_LIST_STREAM_CODEC, CrateLootPayload::items,
                    ByteBufCodecs.STRING_UTF8, CrateLootPayload::tableId, CrateLootPayload::new);

    public CrateLootPayload {
        items = items == null ? List.of() : List.copyOf(items);
        tableId = tableId == null ? "" : tableId;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
