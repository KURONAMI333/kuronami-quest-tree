package com.kuronami.questtree.server;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * The only class that mentions FTB Library's currency hook, and it does so through reflection: the common
 * compile-only artifact carries intermediary parameter names, which would not link against the mapped game. Called
 * only when FTB Library is loaded, so a pack without it never loads this class. Currency mods (FTB Money and others)
 * register the paying half with FTB Library; whichever one is installed is the coin a {@code currency} reward pays.
 * <p>
 * Methods are looked up on the {@code CurrencyProvider} interface, not on the implementation: an implementation may
 * keep them non-public at class level, and calling those raises {@link IllegalAccessException} (2026-10-21 review).
 */
final class FtbLibraryCurrencyBridge {

    private static final String HELPER = "dev.ftb.mods.ftblibrary.integration.currency.CurrencyHelper";
    private static final String PROVIDER = "dev.ftb.mods.ftblibrary.integration.currency.CurrencyProvider";

    private FtbLibraryCurrencyBridge() {
    }

    /** The registered provider, or null when no economy mod is installed or the lookup failed. */
    private static Object provider() {
        try {
            Class<?> helper = Class.forName(HELPER);
            Object instance = helper.getMethod("getInstance").invoke(null);
            Object provider = helper.getMethod("getProvider").invoke(instance);
            if (provider == null) {
                return null;
            }
            Method valid = Class.forName(PROVIDER).getMethod("isValidProvider");
            return Boolean.TRUE.equals(valid.invoke(provider)) ? provider : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }

    /** Whether an economy mod has registered a working provider with FTB Library. */
    static boolean available() {
        return provider() != null;
    }

    static String name() {
        Object provider = provider();
        if (provider == null) {
            return "";
        }
        try {
            return String.valueOf(Class.forName(PROVIDER).getMethod("getName").invoke(provider));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "";
        }
    }

    /** Pays the player. A provider that throws grants nothing and reports failure to the caller. */
    static boolean give(ServerPlayer player, int amount) {
        Object provider = provider();
        if (provider == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName(PROVIDER);
            type.getMethod("giveCurrency", net.minecraft.world.entity.player.Player.class, int.class)
                    .invoke(provider, player, amount);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return false;
        }
    }
}
