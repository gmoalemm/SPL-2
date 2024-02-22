package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Object testLock;

    private long[] freezeTimes;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        // deck = IntStream.range(0,
        // env.config.deckSize).boxed().collect(Collectors.toList());
        this.deck = IntStream.range(0, 21).boxed().collect(Collectors.toList());

        this.testLock = new Object();

        this.freezeTimes = new long[this.players.length];
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        for (Player player : this.players) {
            Thread playerThread = new Thread(player);
            playerThread.start();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            removeAllCardsFromTable();
        }

        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime && !this.table.isEmpty()) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement

        // TODO: handle cases when a player has not finished doing something when this
        // method is called

        for (Player player : this.players) {
            player.terminate();
        }

        this.terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     * 
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        Integer currentPlayer;
        int[] currentPlayerCards = new int[3];
        int i;

        while (!this.table.waitingPlayers.isEmpty()) {
            currentPlayer = this.table.waitingPlayers.poll();

            i = 0;

            // find the cards that the current player chose
            for (int slot = 0; slot < this.env.config.tableSize; slot++) {
                // if the current player placed a token in this slot, add the card to the array
                if (this.table.tokens[slot][currentPlayer]) {
                    currentPlayerCards[i++] = this.table.slotToCard[slot];
                }
            }

            // now check the set

            if (i == 3) {
                if (env.util.testSet(currentPlayerCards)) {
                    this.players[currentPlayer].foundSet = 1;

                    for (int card : currentPlayerCards) {
                        table.removeCard(this.table.cardToSlot[card]);
                    }

                    System.out.println("player " + currentPlayer + " won");
                    this.freezeTimes[currentPlayer] = System.currentTimeMillis() + this.env.config.pointFreezeMillis;
                    this.updateTimerDisplay(true);
                } else {
                    this.players[currentPlayer].foundSet = -1;

                    for (int card : currentPlayerCards) {
                        table.removeToken(currentPlayer, this.table.cardToSlot[card]);
                    }

                    this.freezeTimes[currentPlayer] = System.currentTimeMillis() + this.env.config.penaltyFreezeMillis;
                }
            }

            synchronized (this.players[currentPlayer].playerTestLock) {
                this.players[currentPlayer].playerTestLock.notify();
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);

        int maxSlot = this.env.config.tableSize;

        for (int slot = 0; slot < maxSlot; slot++) {
            if (this.table.slotToCard[slot] == null && !this.deck.isEmpty()) {
                this.table.placeCard(this.deck.remove(0), slot);
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            synchronized (this.testLock) {
                this.testLock.wait(25);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + this.env.config.turnTimeoutMillis;

            this.env.ui.setCountdown(this.env.config.turnTimeoutMillis, false);

            // reshuffleTime = System.currentTimeMillis() + 30 * 1000;
        } else {
            long timeLeft = reshuffleTime - System.currentTimeMillis();

            this.env.ui.setCountdown(timeLeft < 0 ? 0 : timeLeft, timeLeft < this.env.config.turnTimeoutWarningMillis);

            for (int player = 0; player < players.length; player++) {

                if (freezeTimes[player] != 0) {
                    this.env.ui.setFreeze(player, freezeTimes[player] - System.currentTimeMillis());

                    if (freezeTimes[player] <= System.currentTimeMillis()) {
                        freezeTimes[player] = 0;
                    }
                }
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        int maxSlot = this.env.config.tableSize;

        for (int slot = 0; slot < maxSlot; slot++) {
            if (this.table.slotToCard[slot] != null) {
                this.deck.add(this.table.slotToCard[slot]);
                this.table.removeCard(slot);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxPoints = 0, counter = 0, i = 0;
        int[] winners;

        for (Player player : players) {
            if (player.score() > maxPoints) {
                counter = 1;
                maxPoints = player.score();
            } else if (player.score() == maxPoints) {
                counter++;
            }
        }

        winners = new int[counter];

        for (Player player : players) {
            if (player.score() == maxPoints) {
                winners[i++] = player.id;
            }
        }

        this.env.ui.announceWinner(winners);
    }
}
