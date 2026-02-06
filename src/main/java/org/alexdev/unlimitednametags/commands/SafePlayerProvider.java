package org.alexdev.unlimitednametags.commands;

import com.jonahseguin.drink.annotation.OptArg;
import com.jonahseguin.drink.argument.CommandArg;
import com.jonahseguin.drink.exception.CommandExitMessage;
import com.jonahseguin.drink.parametric.DrinkProvider;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Drink player provider that defensively snapshots the online player list.
 * Folia runs tab completion on region threads, so iterating the live player
 * collection can throw ConcurrentModificationException while players join/quit.
 */
public class SafePlayerProvider extends DrinkProvider<Player> {

    private final Plugin plugin;

    public SafePlayerProvider(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean doesConsumeArgument() {
        return true;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public boolean allowNullArgument() {
        return false;
    }

    @Nullable
    @Override
    public Player defaultNullValue() {
        return null;
    }

    @Nullable
    @Override
    public Player provide(@Nonnull CommandArg arg, @Nonnull List<? extends Annotation> annotations) throws CommandExitMessage {
        String name = arg.get();
        Player player = plugin.getServer().getPlayerExact(name);
        if (player != null) {
            return player;
        }

        if (arg.getSender() instanceof Player sender && annotations.stream().anyMatch(a -> a.annotationType() == OptArg.class)) {
            return sender;
        }

        throw new CommandExitMessage("No player online with name '" + name + "'.");
    }

    @Override
    public String argumentDescription() {
        return "player";
    }

    @Override
    public List<String> getSuggestions(@Nonnull String prefix) {
        final String finalPrefix = prefix.toLowerCase();

        try {
            // Snapshot the player list to avoid CME when players join/leave on other region threads
            return List.copyOf(plugin.getServer().getOnlinePlayers())
                    .stream()
                    .map(HumanEntity::getName)
                    .filter(name -> finalPrefix.isEmpty() || name.toLowerCase().startsWith(finalPrefix))
                    .collect(Collectors.toList());
        } catch (ConcurrentModificationException ignored) {
            return Collections.emptyList();
        }
    }
}
