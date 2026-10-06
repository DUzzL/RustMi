package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.cramming.CrammingHooks;
import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.explosion.ExplosionEngine;
import com.github.tatercertified.nalim.sections.NativeSections;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runtime control surface for Rustmi — Fabrolia-style command structure
 * (one toggle per feature, single {@code /rustmi stats} entry for
 * the everything-at-a-glance view).
 *
 * <pre>
 * /rustmi stats                    — all feature toggles
 * /rustmi mathcheck                — live bit-exact Mth parity probe
 * /rustmi cramming on|off          — entity cramming (native Rust path)
 * /rustmi sections on|off          — native palette storage (off-heap)
 * /rustmi collision on|off         — native collision engine
 * /rustmi collision status         — live engine and shadow counters
 * /rustmi explosion on|off         — native explosion engine
 * /rustmi redstone on|off          — R3 cascade takeover
 * </pre>
 */
public final class RustmiCommands {

    private static final Component PREFIX = Component.literal("§8[§bRustmi§8]§7 ");

    private RustmiCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rustmi")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(statsCommand())
                .then(mathCheckCommand())
                .then(toggleCommand("cramming",
                        CrammingHooks::isCrammingEnabled, CrammingHooks::setCrammingEnabled)
                        .then(Commands.literal("status").executes(ctx -> sendLine(ctx,
                                com.github.tatercertified.nalim.cramming.CrammingShadow.status())))
                        .then(toggleCommand("shadow",
                                com.github.tatercertified.nalim.cramming.CrammingShadow::isEnabled,
                                com.github.tatercertified.nalim.cramming.CrammingShadow::setEnabled)))
                .then(toggleCommand("sections",
                        NativeSections::enabled, NativeSections::setEnabled)
                        .then(Commands.literal("wirecheck").executes(ctx -> sendLine(ctx,
                                com.github.tatercertified.nalim.sections.WireCheck.run(ctx.getSource().getLevel())))))
                .then(collisionCommand())
                .then(toggleCommand("collisionshadow",
                        CollisionEngine::isShadow, CollisionEngine::setShadow))
                .then(explosionCommand())
                .then(toggleCommand("redstone",
                        CascadeEngine::isEnabled, CascadeEngine::setEnabled)
                        .then(Commands.literal("status").executes(ctx -> sendLine(ctx, CascadeEngine.status()))))
                .then(toggleCommand("spawncap",
                        com.github.tatercertified.nalim.spawncap.SpawnCapHooks::isEnabled,
                        com.github.tatercertified.nalim.spawncap.SpawnCapHooks::setEnabled)));
    }

    private static int sendLine(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String line) {
        ctx.getSource().sendSuccess(() -> ((MutableComponent) PREFIX.copy())
                .append(Component.literal(line).withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    /** {@code /rustmi stats} — show all feature toggles + sessions. */
    private static LiteralArgumentBuilder<CommandSourceStack> statsCommand() {
        return Commands.literal("stats").executes(ctx -> {
            MutableComponent msg = ((MutableComponent) PREFIX.copy())
                    .append(Component.literal("Status").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            msg.append(Component.literal("\n").withStyle(ChatFormatting.GRAY))
               .append(Component.literal("Native backend: ").withStyle(ChatFormatting.WHITE))
               .append(Component.literal(com.github.tatercertified.nalim.NativeBackend.describe())
                       .withStyle(ChatFormatting.GRAY));

            // Optimization toggles, one per line.
            appendToggle(msg, "Cramming",       CrammingHooks.isCrammingEnabled());
            appendToggle(msg, "Native Sections", NativeSections.enabled());
            appendToggle(msg, "Collision Engine", CollisionEngine.isEngine());
            appendToggle(msg, "Explosion Engine", ExplosionEngine.isEngine());
            appendToggle(msg, "Redstone (R3)",   CascadeEngine.isEnabled());

            // Native sections details — only if there's something to show.
            String sectionsDetail = NativeSections.statusShort();
            if (sectionsDetail != null && !sectionsDetail.isEmpty()) {
                msg.append(Component.literal("\n").withStyle(ChatFormatting.GRAY))
                   .append(Component.literal("Sections: ").withStyle(ChatFormatting.WHITE))
                   .append(Component.literal(sectionsDetail).withStyle(ChatFormatting.GRAY));
            }

            ctx.getSource().sendSuccess(() -> msg, false);
            return 1;
        });
    }

    /** {@code /rustmi mathcheck} — exercise the live, transformed Mth implementation. */
    private static LiteralArgumentBuilder<CommandSourceStack> mathCheckCommand() {
        return Commands.literal("mathcheck").executes(ctx -> {
            String result = MathCheck.run();
            boolean ok = result.startsWith("ВСЁ СОВПАЛО");
            ctx.getSource().sendSuccess(() -> ((MutableComponent) PREFIX.copy())
                    .append(Component.literal(result)
                            .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED)), false);
            return ok ? 1 : 0;
        });
    }

    /** Generic on/off toggle for a single feature. Same response format
     *  for every feature so users know what to expect. */
    private static LiteralArgumentBuilder<CommandSourceStack> toggleCommand(
            String name, BooleanSupplier getter, Consumer<Boolean> setter) {
        return Commands.literal(name)
                .executes(ctx -> {
                    // No subcommand: report current state.
                    boolean state = getter.getAsBoolean();
                    sendToggleResult(ctx, name, state, false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    setter.accept(true);
                    sendToggleResult(ctx, name, true, true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    setter.accept(false);
                    sendToggleResult(ctx, name, false, true);
                    return 1;
                }));
    }

    /** Collision toggle plus the live shadow counters used by the
     *  vanilla-parity benchmark. */
    private static LiteralArgumentBuilder<CommandSourceStack> collisionCommand() {
        return Commands.literal("collision")
                .executes(ctx -> {
                    sendToggleResult(ctx, "collision", CollisionEngine.isEngine(), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    CollisionEngine.setEngine(true);
                    sendToggleResult(ctx, "collision", true, true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    CollisionEngine.setEngine(false);
                    sendToggleResult(ctx, "collision", false, true);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    String status = CollisionEngine.status();
                    ctx.getSource().sendSuccess(() -> ((MutableComponent) PREFIX.copy())
                            .append(Component.literal(status).withStyle(ChatFormatting.GRAY)), false);
                    return 1;
                }));
    }

    /** Explosion subcommand tree — {@code on/off} for the E1/E2 engine, plus the
     *  E3 certification surface: {@code shadow on|off} (Rust prediction vs vanilla,
     *  mismatches logged; the flip gate is a dry status) and {@code status}
     *  (counters: takeovers / fallbacks / shadow ok+bad / seen-percent split). */
    private static LiteralArgumentBuilder<CommandSourceStack> explosionCommand() {
        return Commands.literal("explosion")
                .executes(ctx -> {
                    sendToggleResult(ctx, "explosion", ExplosionEngine.isEngine(), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExplosionEngine.setEngine(true);
                    sendToggleResult(ctx, "explosion", true, true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExplosionEngine.setEngine(false);
                    sendToggleResult(ctx, "explosion", false, true);
                    return 1;
                }))
                .then(Commands.literal("shadow")
                        .then(Commands.literal("on").executes(ctx -> {
                            ExplosionEngine.setShadow(true);
                            sendToggleResult(ctx, "explosion shadow", true, true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            ExplosionEngine.setShadow(false);
                            sendToggleResult(ctx, "explosion shadow", false, true);
                            return 1;
                        })))
                .then(Commands.literal("seenfast")
                        .then(Commands.literal("on").executes(ctx -> {
                            ExplosionEngine.setSeenFast(true);
                            sendToggleResult(ctx, "explosion seenfast", true, true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            ExplosionEngine.setSeenFast(false);
                            sendToggleResult(ctx, "explosion seenfast", false, true);
                            return 1;
                        })))
                .then(Commands.literal("status").executes(ctx -> {
                    String s = ExplosionEngine.status();
                    ctx.getSource().sendSuccess(() -> ((MutableComponent) PREFIX.copy())
                            .append(Component.literal(s).withStyle(ChatFormatting.GRAY)), false);
                    return 1;
                }));
    }

    /** Append a "Feature: ON/OFF" line to a message with consistent
     *  color (green for on, red for off). */
    private static void appendToggle(MutableComponent msg, String label, boolean on) {
        msg.append(Component.literal("\n" + label + ": ").withStyle(ChatFormatting.WHITE))
           .append(Component.literal(on ? "ON" : "OFF")
                   .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    /** Send a uniform "feature is now ON" / "feature is OFF" response.
     *  {@code changed=true} indicates a state change (broadcast), false
     *  indicates a status query (silent). */
    private static void sendToggleResult(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            String name, boolean state, boolean changed) {
        MutableComponent msg = ((MutableComponent) PREFIX.copy())
                .append(Component.literal(name + " ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(state ? "ON" : "OFF")
                        .withStyle(state ? ChatFormatting.GREEN : ChatFormatting.RED));
        if (changed) {
            ctx.getSource().sendSuccess(() -> msg, true);
        } else {
            ctx.getSource().sendSuccess(() -> msg, false);
        }
    }
}
