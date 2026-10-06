package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.redstone.RustmiCommands;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers /rustmi commands without a fabric-api dependency (the nalim
 * subproject deliberately has none). The Commands constructor runs at server
 * start and on /reload — both rebuild the dispatcher, so registering at
 * constructor RETURN keeps the command alive across datapack reloads.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Shadow
    public abstract CommandDispatcher<CommandSourceStack> getDispatcher();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rustmi$registerCommands(Commands.CommandSelection selection,
                                            CommandBuildContext context,
                                            CallbackInfo ci) {
        RustmiCommands.register(this.getDispatcher());
    }
}
