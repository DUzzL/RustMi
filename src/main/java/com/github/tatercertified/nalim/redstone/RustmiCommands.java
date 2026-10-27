package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.cramming.CrammingHooks;
import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.explosion.ExplosionEngine;
import com.github.tatercertified.nalim.sections.NativeSections;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The {@code /rustmi} command tree (ops only): one on/off toggle per feature, plus shadow
 * (compare-with-vanilla) modes and status counters.
 *
 * <pre>
 * /rustmi stats                           — native backend, every toggle, section memory
 * /rustmi cramming on|off|status          — batched entity pushing (Rust kernel)
 * /rustmi cramming shadow on|off          — re-run vanilla and count set/order mismatches
 * /rustmi collision on|off|status         — mirror-backed block collision
 * /rustmi collisionshadow on|off          — compare every collision with vanilla
 * /rustmi explosion on|off|status         — native explosion block sweep
 * /rustmi explosion shadow|seenfast on|off
 * /rustmi sections on|off|wirecheck       — off-heap block-state storage
 * /rustmi redstone on|off|status          — redstone-wire cascade takeover (R3)
 * /rustmi spawncap on|off                 — native local mob-cap (with Fabrolia)
 * </pre>
 *
 * tools/parity/drive.py parses the status lines with regexes: change their wording
 * together with the harness.
 */
public final class RustmiCommands {

    private static final Component PREFIX = Component.literal("§8[§bRustmi§8]§7 ");

    private RustmiCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rustmi")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(statsCommand())
                .then(toggleCommand("cramming",
                        CrammingHooks::isCrammingEnabled, CrammingHooks::setCrammingEnabled)
                        .then(Commands.literal("status").executes(ctx -> sendLine(ctx,
                                com.github.tatercertified.nalim.cramming.CrammingShadow.status())))
                        .then(toggleCommand("shadow",
                                com.github.tatercertified.nalim.cramming.CrammingShadow::isEnabled,
                                com.github.tatercertified.nalim.cramming.CrammingShadow::setEnabled)))
                .then(toggleCommand("sections",
                        NativeSections::enabled, NativeSections::setEnabled)
                        .then(adminLiteral("wirecheck").executes(ctx -> sendLine(ctx,
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

    /** Switches and heavy checks need admin level (3): they persist engine state and can
     *  stall the tick. Reading stats/status stays at the root's gamemaster level (2), so
     *  command blocks cannot flip engines. */
    private static LiteralArgumentBuilder<CommandSourceStack> adminLiteral(String name) {
        return Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_ADMINS));
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
                .then(adminLiteral("on").executes(ctx -> {
                    setter.accept(true);
                    sendToggleResult(ctx, name, getter.getAsBoolean(), true);
                    return 1;
                }))
                .then(adminLiteral("off").executes(ctx -> {
                    setter.accept(false);
                    sendToggleResult(ctx, name, getter.getAsBoolean(), true);
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
                .then(adminLiteral("on").executes(ctx -> {
                    CollisionEngine.setEngine(true);
                    sendToggleResult(ctx, "collision", true, true);
                    return 1;
                }))
                .then(adminLiteral("off").executes(ctx -> {
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
                .then(adminLiteral("on").executes(ctx -> {
                    ExplosionEngine.setEngine(true);
                    sendToggleResult(ctx, "explosion", true, true);
                    return 1;
                }))
                .then(adminLiteral("off").executes(ctx -> {
                    ExplosionEngine.setEngine(false);
                    sendToggleResult(ctx, "explosion", false, true);
                    return 1;
                }))
                .then(Commands.literal("shadow")
                        .then(adminLiteral("on").executes(ctx -> {
                            ExplosionEngine.setShadow(true);
                            sendToggleResult(ctx, "explosion shadow", true, true);
                            return 1;
                        }))
                        .then(adminLiteral("off").executes(ctx -> {
                            ExplosionEngine.setShadow(false);
                            sendToggleResult(ctx, "explosion shadow", false, true);
                            return 1;
                        })))
                .then(Commands.literal("seenfast")
                        .then(adminLiteral("on").executes(ctx -> {
                            ExplosionEngine.setSeenFast(true);
                            sendToggleResult(ctx, "explosion seenfast", true, true);
                            return 1;
                        }))
                        .then(adminLiteral("off").executes(ctx -> {
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
