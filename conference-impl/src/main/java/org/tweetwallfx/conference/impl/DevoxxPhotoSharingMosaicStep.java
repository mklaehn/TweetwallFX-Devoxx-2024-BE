/*
 * MIT License
 *
 * Copyright (c) 2016-2024 TweetWallFX
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.tweetwallfx.conference.impl;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Transition;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.CacheHint;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

import org.slf4j.LoggerFactory;
import org.tweetwallfx.controls.WordleSkin;
import org.tweetwallfx.controls.steps.ImageMosaicStep;
import org.tweetwallfx.stepengine.api.DataProvider;
import org.tweetwallfx.stepengine.api.Step;
import org.tweetwallfx.stepengine.api.StepEngine.MachineContext;
import org.tweetwallfx.stepengine.api.config.AbstractConfig;
import org.tweetwallfx.stepengine.api.config.StepEngineSettings;
import org.tweetwallfx.stepengine.dataproviders.ImageStorage;
import org.tweetwallfx.transitions.LocationTransition;
import org.tweetwallfx.transitions.SizeTransition;
import org.tweetwallfx.util.JsonDataConverter;

public class DevoxxPhotoSharingMosaicStep implements Step {

    private final Config config;
    private static final Random RANDOM = new SecureRandom();
    private final ImageView[][] rects;
    private final Bounds[][] bounds;
    private final Set<Integer> highlightedIndexes = new HashSet<>();
    private Pane pane;
    private int count = 0;
    private final Image qrCode = new Image(this.getClass().getResourceAsStream("/photos-devoxx-be-qr.jpeg"));

    private DevoxxPhotoSharingMosaicStep(Config config) {
        this.config = config;
        rects = new ImageView[config.columns][config.rows];
        bounds = new Bounds[config.columns][config.rows];
    }

    @Override
    public boolean shouldSkip(final MachineContext context) {
        boolean forceSkipping = null == config.skipWhenSkipped
                ? false
                : config.skipWhenSkipped.equals(context.get(Step.SKIP_TOKEN));
        boolean notEnoughImagesAvailable = context.getDataProvider(DevoxxPhotoSharingDataProvider.class)
                .getAccess().count() < config.getMinimumNumberOfImagesInCacheCalculated();

        boolean skip = forceSkipping || notEnoughImagesAvailable;
        return skip;
    }

    @Override
    public void doStep(final MachineContext context) {
        WordleSkin wordleSkin = (WordleSkin) context.get("WordleSkin");
        DevoxxPhotoSharingDataProvider dataProvider = context.getDataProvider(DevoxxPhotoSharingDataProvider.class);
        pane = wordleSkin.getPane();
        Transition createMosaicTransition = createMosaicTransition(dataProvider.getAccess().getImages(config.getNumberOfImagesToChooseFromCalculated()));
        createMosaicTransition.setOnFinished(event
                -> executeAnimations(context));

        createMosaicTransition.play();
    }

    @Override
    public java.time.Duration preferredStepDuration(final MachineContext context) {
        return config.stepDuration();
    }

    private void executeAnimations(final MachineContext context) {
        ImageWallAnimationTransition highlightAndZoomTransition
                = createHighlightAndZoomTransition();
        highlightAndZoomTransition.transition.play();
        highlightAndZoomTransition.transition.setOnFinished(event1 -> {
            Transition revert
                    = createReverseHighlightAndZoomTransition(highlightAndZoomTransition.column, highlightAndZoomTransition.row);
            revert.setDelay(Duration.seconds(3));
            revert.play();
            revert.setOnFinished(event -> {
                count++;
                if (count < config.numberOfHighlights) {
                    executeAnimations(context);
                } else {
                    count = 0;
                    ParallelTransition cleanup = new ParallelTransition();
                    for (int i = 0; i < config.columns; i++) {
                        for (int j = 0; j < config.rows; j++) {
                            FadeTransition ft = new FadeTransition(Duration.seconds(0.5), rects[i][j]);
                            ft.setToValue(0);
                            cleanup.getChildren().addAll(ft);
                        }
                    }
                    cleanup.setOnFinished(cleanUpDown -> {
                        for (int i = 0; i < config.columns; i++) {
                            for (int j = 0; j < config.rows; j++) {
                                pane.getChildren().remove(rects[i][j]);
                            }
                        }
                        highlightedIndexes.clear();
                        context.proceed();
                    });
                    cleanup.play();
                }
            });
        });
    }

    private Transition createMosaicTransition(final List<ImageStorage> imageStorages) {
        final SequentialTransition fadeIn = new SequentialTransition();
        final List<FadeTransition> allFadeIns = new ArrayList<>();
        final double width = (0 != config.width ? config.width : pane.getWidth()) / (double) config.columns - 10;
        final double height = (0 != config.height ? config.height : pane.getHeight()) / (double) config.rows - 8;
        final int posOfQrCodeX = RANDOM.nextInt(config.columns);
        final int posOfQrCodeY = RANDOM.nextInt(config.rows);
        final List<ImageStorage> distillingList = imageStorages; // mutable list required

        for (int i = 0; i < config.columns; i++) {
            for (int j = 0; j < config.rows; j++) {
                int index = RANDOM.nextInt(distillingList.size());
                Image selectedImage = (i == posOfQrCodeX && j == posOfQrCodeY) ? qrCode : distillingList.remove(index).getImage();
                ImageView imageView = new ImageView(selectedImage);
                imageView.setCache(true);
                imageView.setCacheHint(CacheHint.SPEED);
                imageView.setFitWidth(width);
                imageView.setFitHeight(height);
//                imageView.setEffect(new GaussianBlur(0));
                rects[i][j] = imageView;
                bounds[i][j] = new BoundingBox(i * (width + 10) + 5 + config.layoutX, j * (height + 8) + 4 + config.layoutY, width, height);
                rects[i][j].setOpacity(0);
                rects[i][j].setLayoutX(bounds[i][j].getMinX());
                rects[i][j].setLayoutY(bounds[i][j].getMinY());
                pane.getChildren().add(rects[i][j]);
                FadeTransition ft = new FadeTransition(Duration.seconds(0.3), imageView);
                ft.setToValue(1);
                allFadeIns.add(ft);
            }
        }
        Collections.shuffle(allFadeIns);
        fadeIn.getChildren().addAll(allFadeIns);
        return fadeIn;
    }

    private ImageWallAnimationTransition createHighlightAndZoomTransition() {
        // select next random not but not previously shown image
        int index;
        do {
            index = RANDOM.nextInt(config.columns * config.rows);
        } while (!highlightedIndexes.add(index));

        int column = index % config.columns;
        int row = index / config.columns;

        ImageView randomView = rects[column][row];
        randomView.toFront();
        ParallelTransition firstParallelTransition = new ParallelTransition();
        ParallelTransition secondParallelTransition = new ParallelTransition();

        for (int i = 0; i < config.columns; i++) {
            for (int j = 0; j < config.rows; j++) {
                if ((i == column) && (j == row)) {
                    continue;
                }
                FadeTransition ft = new FadeTransition(Duration.seconds(1), rects[i][j]);
                ft.setToValue(0.3);
                firstParallelTransition.getChildren().add(ft);
            }
        }
        for (int i = 0; i < config.columns; i++) {
            for (int j = 0; j < config.rows; j++) {
                if ((i == column) && (j == row)) {
                    continue;
                }

                GaussianBlur blur = (GaussianBlur) rects[i][j].getEffect();
                if (null == blur) {
                    blur = new GaussianBlur(0);
                    rects[i][j].setEffect(blur);
                }
//                BlurTransition blurTransition = new BlurTransition(Duration.seconds(0.5), blur);
//                blurTransition.setToRadius(10);
//                secondParallelTransition.getChildren().addAll(blurTransition);
            }
        }

        double maxWidth = (0 != config.width ? config.width : pane.getWidth()) * config.percentageForHighlightImage;
        double maxHeight = (0 != config.height ? config.height : pane.getHeight()) * config.percentageForHighlightImage;

        double realWidth = randomView.getImage().getWidth();
        double realHeight = randomView.getImage().getHeight();

        double scaleFactor = Math.min(maxWidth / realWidth, maxHeight / realHeight);

        double targetWidth = realWidth * scaleFactor;
        double targetheight = realHeight * scaleFactor;

        final SizeTransition zoomBox = new SizeTransition(Duration.seconds(config.resizeAndHighlightTransitionTime),
                randomView.fitWidthProperty(), randomView.fitHeightProperty())
                .withWidth(randomView.getLayoutBounds().getWidth(), targetWidth)
                .withHeight(randomView.getLayoutBounds().getHeight(), targetheight);
        final LocationTransition trans = new LocationTransition(Duration.seconds(config.resizeAndHighlightTransitionTime), randomView)
                .withX(randomView.getLayoutX(), (0 != config.width ? config.width : pane.getWidth()) / 2 - targetWidth / 2 + config.layoutX)
                .withY(randomView.getLayoutY(), (0 != config.height ? config.height : pane.getHeight()) / 2 - targetheight / 2 + config.layoutY);
        secondParallelTransition.getChildren().addAll(trans, zoomBox);

        SequentialTransition seqT = new SequentialTransition();
        seqT.getChildren().addAll(firstParallelTransition, secondParallelTransition);

        firstParallelTransition.setOnFinished(event -> {
//            DropShadow ds = new DropShadow();
//            ds.setOffsetY(10.0);
//            ds.setOffsetX(10.0);
//            ds.setColor(Color.GRAY);
//            randomView.setEffect(ds);
        });

        return new ImageWallAnimationTransition(seqT, column, row);
    }

    private Transition createReverseHighlightAndZoomTransition(final int column, final int row) {
        ImageView randomView = rects[column][row];
        randomView.toFront();
        ParallelTransition firstParallelTransition = new ParallelTransition();
        ParallelTransition secondParallelTransition = new ParallelTransition();

        for (int i = 0; i < config.columns; i++) {
            for (int j = 0; j < config.rows; j++) {
                if ((i == column) && (j == row)) {
                    continue;
                }
                FadeTransition ft = new FadeTransition(Duration.seconds(1), rects[i][j]);
                ft.setFromValue(0.3);
                ft.setToValue(1.0);
                firstParallelTransition.getChildren().add(ft);
            }
        }

        final double width = (0 != config.width ? config.width : pane.getWidth()) / (double) config.columns - 10;
        final double height = (0 != config.height ? config.height : pane.getHeight()) / (double) config.rows - 8;

        final SizeTransition zoomBox = new SizeTransition(Duration.seconds(config.resizeAndHighlightTransitionTime),
                randomView.fitWidthProperty(), randomView.fitHeightProperty())
                .withWidth(randomView.getLayoutBounds().getWidth(), width)
                .withHeight(randomView.getLayoutBounds().getHeight(), height);
        final LocationTransition trans = new LocationTransition(Duration.seconds(config.resizeAndHighlightTransitionTime), randomView)
                .withX(randomView.getLayoutX(), bounds[column][row].getMinX())
                .withY(randomView.getLayoutY(), bounds[column][row].getMinY());
        secondParallelTransition.getChildren().addAll(trans, zoomBox);

        SequentialTransition seqT = new SequentialTransition();
        seqT.getChildren().addAll(secondParallelTransition, firstParallelTransition);

        secondParallelTransition.setOnFinished(event
                -> randomView.setEffect(null));

        return seqT;
    }

    private static class ImageWallAnimationTransition {

        private final Transition transition;
        private final int column;
        private final int row;

        public ImageWallAnimationTransition(final Transition transition, final int column, final int row) {
            this.transition = transition;
            this.column = column;
            this.row = row;
        }
    }

    /**
     * Implementation of {@link Step.Factory} as Service implementation creating
     * {@link ImageMosaicStep}.
     */
    public static final class FactoryImpl implements Step.Factory {

        @Override
        public DevoxxPhotoSharingMosaicStep create(final StepEngineSettings.StepDefinition stepDefinition) {
            final Config c = stepDefinition.getConfig(Config.class);
            LoggerFactory.getLogger(Factory.class).info("stepConfig: {}", c);
            return new DevoxxPhotoSharingMosaicStep(c);
        }

        @Override
        public Class<DevoxxPhotoSharingMosaicStep> getStepClass() {
            return DevoxxPhotoSharingMosaicStep.class;
        }

        @Override
        public Collection<Class<? extends DataProvider>> getRequiredDataProviders(final StepEngineSettings.StepDefinition stepSettings) {
            return Arrays.asList(DevoxxPhotoSharingDataProvider.class);
        }
    }

    public static class Config extends AbstractConfig {

        public double layoutX = 0D;
        public double layoutY = 0D;
        public double width = 0D;
        public double height = 0D;
        public int columns = 6;
        public int rows = 5;
        public String skipWhenSkipped;
        public int minimumNumberOfImagesInCache = -1;

        private int getMinimumNumberOfImagesInCacheCalculated() {
            return minimumNumberOfImagesInCache > 0
                    ? minimumNumberOfImagesInCache
                    : columns * rows + Math.max(columns, rows);
        }

        public int numberOfImagesToChooseFrom = -1;
        public double numberOfImagesToChooseFromExtension = 1.4D;

        private int getNumberOfImagesToChooseFromCalculated() {
            return numberOfImagesToChooseFrom > 0
                    ? numberOfImagesToChooseFrom
                    : (int) (numberOfImagesToChooseFromExtension * columns * rows);
        }

        public double percentageForHighlightImage = 0.8D;
        public double resizeAndHighlightTransitionTime = 2.5D;
        public int numberOfHighlights = 3;
    }
}
