package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.feature.IFeature;
import com.jelly.farmhelperv2.handler.MacroHandler;
import com.jelly.farmhelperv2.util.AngleUtils;
import com.jelly.farmhelperv2.util.KeyBindUtils;
import com.jelly.farmhelperv2.util.LogUtils;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.lang3.tuple.MutablePair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static cc.polyfrost.oneconfig.libs.universal.UMath.wrapAngleTo180;

/*
    Credits to Yuro for this superb class
    https://github.com/onixiya1337/MovementRecorder
*/

public class MovRecPlayer implements IFeature {

    // region BOOLEANS, LISTS, ETC
    private static final List<Movement> movements = new ArrayList<>();
    static Minecraft mc = Minecraft.getMinecraft();
    private static MovRecPlayer instance;
    private static boolean isMovementPlaying = false;
    private static boolean isMovementReading = false;
    private static int currentDelay = 0;
    private static int playingIndex = 0;
    private static float yawDifference = 0;
    @Setter
    public String recordingName = "";
    @Setter
    private boolean builtIn = true;
    // endregion

    // region CONSTRUCTOR

    public static MovRecPlayer getInstance() {
        if (instance == null) {
            instance = new MovRecPlayer();
        }
        return instance;
    }

    private static void resetTimers() {
        resetRotation();
    }

    @Override
    public String getName() {
        return "Movement Recording Player";
    }

    @Override
    public boolean isRunning() {
        return isMovementReading || isMovementPlaying;
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return true;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false;
    }

    @Override
    public boolean isToggled() {
        return false;
    }

    @Override
    public boolean shouldCheckForFailsafes() {
        return false;
    }

    @Override
    public void resetStatesAfterMacroDisabled() {
        playingIndex = 0;
        currentDelay = 0;
        isMovementPlaying = false;
        isMovementReading = false;
        recordingName = "";
        builtIn = true;
        resetTimers();
    }

    // endregion

    public void playRandomRecording(String pattern, boolean builtIn) {
        File[] files;
        String filename = "";

        // get random recording
        if (builtIn) {
            String filePath = "/farmhelper/movrec/";
            URL resourceUrl = getClass().getResource(filePath);
            if (resourceUrl != null)
                files = new File(resourceUrl.getPath()).listFiles((dir, name) -> name.contains(pattern) && name.endsWith(".movement"));
            else {
                if (Failsafe.getInstance().isRunning()) {
                    Failsafe.getInstance().handleRecordingError(3);
                }
                return;
            }
        } else {
            File recordingDir = new File(mc.mcDataDir, "movementrecorder");
            files = recordingDir.listFiles((dir, name) -> name.contains(pattern) && name.endsWith(".movement"));
        }

        if (files != null && files.length > 0) {
            List<File> matchingFiles = new ArrayList<>(Arrays.asList(files));

            Random random = new Random();
            int randomIndex = random.nextInt(matchingFiles.size());
            LogUtils.sendDebug("[Movement Recorder] Selected recording: " + matchingFiles.get(randomIndex).getName());
            filename = matchingFiles.get(randomIndex).getName();
        }
        if (filename.isEmpty()) {
            LogUtils.sendError("[Movement Recorder] No recording found!");
            if (Failsafe.getInstance().isRunning()) {
                LogUtils.sendWarning("[Movement Recorder] Your recording is probably corrupted! Try to record it again. Switching to built-in failsafe mechanism instead.");
                Failsafe.getInstance().resetCustomMovement();
                Failsafe.getInstance().setReactionType(Failsafe.getInstance().getReactionType() + 1);
            }
            stop();
            resetStatesAfterMacroDisabled();
            return;
        }
        MovRecPlayer movRecPlayer = new MovRecPlayer();
        movRecPlayer.setRecordingName(filename);
        movRecPlayer.builtIn = builtIn;
        movRecPlayer.start();
    }

    @Override
    public void start() {
        if (recordingName.isEmpty()) {
            LogUtils.sendError("[Movement Recorder] No recording selected!");
            if (Failsafe.getInstance().isRunning())
                Failsafe.getInstance().handleRecordingError(2);
            return;
        }
        if (isMovementPlaying) {
            LogUtils.sendDebug("[Movement Recorder] The recording is playing already.");
            return;
        }
        movements.clear();
        playingIndex = 0;
        resetTimers();
        isMovementReading = true;
        try {
            List<String> lines;
            lines = read();
            if (lines == null) {
                if (Failsafe.getInstance().isRunning())
                    Failsafe.getInstance().handleRecordingError(3);
                return;
            }
            for (String line : lines) {
                if (!isMovementReading)
                    return;
                movements.add(getMovement(line));
            }
        } catch (Exception e) {
            LogUtils.sendError("[Movement Recorder] An error occurred while playing the recording.");
            e.printStackTrace();
            if (Failsafe.getInstance().isRunning()) {
                LogUtils.sendWarning("[Movement Recorder] Your recording is corrupted! Try to record it again. Switching to built-in failsafe mechanism instead.");
                Failsafe.getInstance().resetCustomMovement();
            }
            stop();
            resetStatesAfterMacroDisabled();
            return;
        }
        isMovementReading = false;
        isMovementPlaying = true;
        Movement movement = movements.get(0);
        yawDifference = AngleUtils.normalizeAngle(AngleUtils.getClosest() - movement.yaw);
        easeTo(movement.yaw + yawDifference , movement.pitch, 500);
    }

    @Override
    public void stop() {
        KeyBindUtils.stopMovement();
        if (isMovementPlaying || isMovementReading) {
            LogUtils.sendDebug("[Movement Recorder] Playing has been stopped.");
            return;
        }
        LogUtils.sendDebug("[Movement Recorder] No recording has been started.");
    }

    @SubscribeEvent
    public void onTickPlayMovement(TickEvent.ClientTickEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null)
            return;
        if (event.phase == TickEvent.Phase.START)
            return;
        if (!isMovementPlaying || isMovementReading)
            return;
        if (movements.isEmpty()) {
            LogUtils.sendError("[Movement Recorder] The file is empty!");
            if (Failsafe.getInstance().isRunning()) {
                LogUtils.sendWarning("[Movement Recorder] Your recording is corrupted! Try to record it again. Switching to built-in failsafe mechanism instead.");
                Failsafe.getInstance().resetCustomMovement();
            }
            stop();
            resetStatesAfterMacroDisabled();
            return;
        }
        if (!MacroHandler.getInstance().isMacroToggled()) {
            LogUtils.sendDebug("[Movement Recorder] Macro has been disabled. Stopping playing.");
            stop();
            resetStatesAfterMacroDisabled();
            return;
        }
        if (rotating) {
            KeyBindUtils.stopMovement();
            return;
        }

        Movement movement = movements.get(playingIndex);
        setPlayerMovement(movement);
        easeTo(movement.yaw + yawDifference, movement.pitch, 500);

        if (currentDelay < movement.delay) {
            currentDelay++;
            return;
        }
        playingIndex++;
        currentDelay = 0;
        if (playingIndex >= movements.size()) {
            isMovementPlaying = false;
            resetTimers();
            LogUtils.sendDebug("[Movement Recorder] Playing has been finished.");
            stop();
            resetStatesAfterMacroDisabled();
        }
    }

    // region HELPER_METHODS

    public static class Movement {
        private final boolean forward;
        private final boolean left;
        private final boolean backwards;
        private final boolean right;
        private final boolean sneak;
        private final boolean sprint;
        private final boolean fly;
        private final boolean jump;
        private final boolean attack;
        private final float yaw;
        private final float pitch;
        private final int delay;

        public Movement(boolean forward, boolean left, boolean backwards, boolean right, boolean sneak, boolean sprint, boolean fly,
                        boolean jump, boolean attack, float yaw, float pitch, int delay) {
            this.forward = forward;
            this.left = left;
            this.backwards = backwards;
            this.right = right;
            this.sneak = sneak;
            this.sprint = sprint;
            this.fly = fly;
            this.jump = jump;
            this.attack = attack;
            this.yaw = yaw;
            this.pitch = pitch;
            this.delay = delay;
        }
    }

    @NotNull
    private static Movement getMovement(String line) {
        String[] split = line.split(";");
        return new Movement(
                Boolean.parseBoolean(split[0]),
                Boolean.parseBoolean(split[1]),
                Boolean.parseBoolean(split[2]),
                Boolean.parseBoolean(split[3]),
                Boolean.parseBoolean(split[4]),
                Boolean.parseBoolean(split[5]),
                Boolean.parseBoolean(split[6]),
                Boolean.parseBoolean(split[7]),
                Boolean.parseBoolean(split[8]),
                Float.parseFloat(split[9]),
                Float.parseFloat(split[10]),
                Integer.parseInt(split[11])
        );
    }

    private void setPlayerMovement(Movement movement) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), movement.forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), movement.left);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), movement.backwards);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), movement.right);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), movement.sneak);
        mc.thePlayer.setSprinting(movement.sprint);
        if (mc.thePlayer.capabilities.allowFlying && mc.thePlayer.capabilities.isFlying != movement.fly)
            mc.thePlayer.capabilities.isFlying = movement.fly;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), movement.jump);
        if (movement.attack && currentDelay == 0)
            KeyBindUtils.leftClick();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), movement.attack);
    }

    @Nullable
    private List<String> read() {
        List<String> lines;
        if (builtIn)
            try {
                String filePath = "/farmhelper/movrec/" + recordingName;
                java.net.URL resourceUrl = getClass().getResource(filePath);
                if (resourceUrl != null) {
                    lines = Files.readAllLines(Paths.get(resourceUrl.toURI()));
                } else {
                    System.out.println("Resource not found: " + filePath);
                    return null;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        else
            try {
                lines = Files.readAllLines(new File(mc.mcDataDir + "\\movementrecorder\\" + recordingName).toPath());
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        return lines;
    }

    // endregion

    // OLD_ROTATIONUTILS

    public static boolean rotating;
    public static boolean completed;

    private long startTime;
    private long endTime;

    MutablePair<Float, Float> start = new MutablePair<>(0f, 0f);
    MutablePair<Float, Float> target = new MutablePair<>(0f, 0f);
    MutablePair<Float, Float> difference = new MutablePair<>(0f, 0f);

    public void easeTo(float yaw, float pitch, long time) {
        completed = false;
        rotating = true;
        startTime = System.currentTimeMillis();
        endTime = System.currentTimeMillis() + time;
        start.setLeft(mc.thePlayer.rotationYaw);
        start.setRight(mc.thePlayer.rotationPitch);
        MutablePair<Float, Float> neededChange = getNeededChange(start, new MutablePair<>(yaw, pitch));
        target.setLeft(start.left + neededChange.left);
        target.setRight(start.right + neededChange.right);
        getDifference();
    }

    public static MutablePair<Float, Float> getNeededChange(MutablePair<Float, Float> startRot, MutablePair<Float, Float> endRot) {
        float yawDiff = (float) (wrapAngleTo180(endRot.getLeft()) - wrapAngleTo180(startRot.getLeft()));

        yawDiff = AngleUtils.normalizeAngle(yawDiff);

        return new MutablePair<>(yawDiff, endRot.getRight() - startRot.right);
    }


    @SubscribeEvent
    public void onWorldLastRender(RenderWorldLastEvent event) {
        if (System.currentTimeMillis() <= endTime) {
            mc.thePlayer.rotationYaw = interpolate(start.getLeft(), target.getLeft());
            mc.thePlayer.rotationPitch = interpolate(start.getRight(), target.getRight());
        } else if (!completed) {
            mc.thePlayer.rotationYaw = target.left;
            mc.thePlayer.rotationPitch = target.right;
            completed = true;
            rotating = false;
        }
    }

    public static void resetRotation() {
        completed = false;
        rotating = false;
    }

    private void getDifference() {
        difference.setLeft(AngleUtils.smallestAngleDifference(AngleUtils.get360RotationYaw(), target.left));
        difference.setRight(target.right - start.right);
    }

    private float interpolate(float start, float end) {
        return (end - start) * easeOutCubic((float) (System.currentTimeMillis() - startTime) / (endTime - startTime)) + start;
    }

    public float easeOutCubic(double number) {
        return (float) Math.max(0, Math.min(1, 1 - Math.pow(1 - number, 3)));
    }

    // endregion
}
