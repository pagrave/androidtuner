/** Copyright (C) 2009 by Aleksey Surkov.
 **
 ** Permission to use, copy, modify, and distribute this software and its
 ** documentation for any purpose and without fee is hereby granted, provided
 ** that the above copyright notice appear in all copies and that both that
 ** copyright notice and this permission notice appear in supporting
 ** documentation.  This software is provided "as is" without express or
 ** implied warranty.
 */

package com.example.AndroidTuner;

import java.lang.Runnable;
import java.lang.Thread;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.app.AlertDialog;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Handler;
import android.util.Log;

public class PitchDetector implements Runnable {
	private static String LOG_TAG = "PitchDetector";

	private AudioRecord recorder_;
	
	private PitchListener pcl;
	
	// Currently, only this combination of rate, encoding and channel mode
	// actually works.
	private final static int RATE = 8000;
	private final static int CHANNEL_MODE = AudioFormat.CHANNEL_CONFIGURATION_MONO;
	private final static int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

	private final static int BUFFER_SIZE_IN_MS = 3000;
	private final static int CHUNK_SIZE_IN_SAMPLES = 4096; // = 2 ^
															// CHUNK_SIZE_IN_SAMPLES_POW2
	private final static int CHUNK_SIZE_IN_MS = 1000 * CHUNK_SIZE_IN_SAMPLES
			/ RATE;
	private final static int BUFFER_SIZE_IN_BYTES = RATE * BUFFER_SIZE_IN_MS
			/ 1000 * 2;
	private final static int CHUNK_SIZE_IN_BYTES = RATE * CHUNK_SIZE_IN_MS
			/ 1000 * 2;

	private final static int MIN_FREQUENCY = 49; // 49.0 HZ of G1 - lowest note
													// for crazy Russian choir.
	private final static int MAX_FREQUENCY = 1568; // 1567.98 HZ of G6 - highest
													// demanded note in the
													// classical repertoire

	private final static int DRAW_FREQUENCY_STEP = 5;

	public final static String[] notes = {"a", "a#", "b", "c", "c#", "d", "d#", "e", "f", "f#", "g", "g#"};
	
	public static native void DoFFT(double[] data, int size); // an NDK library
														// 'fft-jni'
	
	
	public static double noiseLevel = 40000.0; // should be measured, eg 2e12 is a normal amplitude for singing.
	public static boolean isNoiseInitialized = false;

	public interface PitchListener{
		public void onAnalysis(FreqResult fr);
		public void onError(String error);
	}
	
	public PitchDetector(PitchListener pcl) {
		this.pcl = pcl;
		System.loadLibrary("fft-jni");
	}

	public void run() {
		Log.e(LOG_TAG, "starting to detect pitch");

		android.os.Process
				.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
		recorder_ = new AudioRecord(AudioSource.MIC, RATE, CHANNEL_MODE,
				ENCODING, 6144);
		if (recorder_.getState() != AudioRecord.STATE_INITIALIZED) {
			this.pcl.onError("Can't initialize AudioRecord");
			return;
		}

		recorder_.startRecording();
		int res;
		while (!Thread.interrupted()) {
			//short[] audio_data = new short[BUFFER_SIZE_IN_BYTES / 2];
			//recorder_.read(audio_data, 0, CHUNK_SIZE_IN_BYTES / 2);
			short[] audio_data = new short[CHUNK_SIZE_IN_BYTES / 2];
			res = recorder_.read(audio_data, 0, audio_data.length);
			if (res == AudioRecord.ERROR_INVALID_OPERATION) {
				Log.e(LOG_TAG, "audio record failed...");
			}
			FreqResult fr = AnalyzeFrequencies(audio_data);
			this.pcl.onAnalysis(fr);
			//PostToUI(fr.frequencies, fr.best_frequency);
		}
		recorder_.stop();
	}	
	
	public static class FreqResult {
		public HashMap<Double, Double> frequencies;
		public double bestFrequency;
		public boolean isPitchDetected;
		public double noiseLevel;
		
		public FreqResult() {
			this.frequencies = null;
			this.bestFrequency = 0.0;
			this.isPitchDetected = false;
			this.noiseLevel = 0.0;
		}
		
//		
//		public FreqResult(HashMap<Double, Double> frequencies, double bestFrequency, boolean isPitchDetected) {
//			this.frequencies = frequencies;
//			this.bestFrequency = bestFrequency;
//			this.isPitchDetected = isPitchDetected;
//		}
//		
		
		@Override public String toString() {
			return "<FreqResult: " + bestFrequency + " Hz>";
		}
	}

	public static class FrequencyCluster {
		public double average_frequency = 0;
		public double total_amplitude = 0;
		
		public void add(double freq, double amplitude) {
			double new_total_amp = total_amplitude + amplitude;
			average_frequency = (total_amplitude * average_frequency + freq * amplitude) / new_total_amp;
			total_amplitude = new_total_amp;
		}
		
		public boolean isNear(double freq) {
			if (Math.abs(1 - (average_frequency / freq)) < 0.05) {
				// only 5% difference
				return true;
			} else {
				return false;
			}
		}
		
		public boolean isHarmonic(double freq) {
			double harmonic_factor = freq / average_frequency;
			double distance_from_int = Math.abs(Math.round(harmonic_factor) - harmonic_factor);
			if (distance_from_int < 0.05) {
				// only 5% distance
				return true;
			} else {
				return false;
			}			
		}

		public void addHarmony(double freq, double amp) {
			total_amplitude += amp;
		}
		
		@Override public String toString() {
			return "(" + average_frequency + ", " + total_amplitude + ")";
		}
	}
	
	public static FreqResult AnalyzeFrequencies(short[] audio_data) {
		FreqResult fr = new FreqResult();

		if (audio_data.length * 2 < 0) {
			Log.e(LOG_TAG, "awkward fail");
		}
		
		if (audio_data.length * 2 > 32000) {
			Log.e(LOG_TAG, "awkwarder fail size:" + (audio_data.length * 2));
		}
		
		double[] data = new double[audio_data.length * 2];
		
		//final int min_frequency_fft = Math.round(MIN_FREQUENCY * CHUNK_SIZE_IN_SAMPLES / RATE);
		//final int max_frequency_fft = Math.round(MAX_FREQUENCY * CHUNK_SIZE_IN_SAMPLES / RATE);
		final int min_frequency_fft = Math.round(MIN_FREQUENCY * audio_data.length / RATE);
		final int max_frequency_fft = Math.round(MAX_FREQUENCY * audio_data.length / RATE);

		
		// TODO: Somewhere in this for loop there's a crash!
		//for (int i = 0; i < CHUNK_SIZE_IN_SAMPLES; i++) {
		for (int i = 0; i < audio_data.length; i++) {
			data[i * 2] = audio_data[i];
			data[i * 2 + 1] = 0;
		}
		
		//DoFFT(data, CHUNK_SIZE_IN_SAMPLES);
		DoFFT(data, audio_data.length);
		
		boolean pitchDetected = false;
		double best_frequency = 0;
		double best_amplitude = 0;
		HashMap<Double, Double> frequencies = new HashMap<Double, Double>();
		final double draw_frequency_step = 1.0 * RATE / CHUNK_SIZE_IN_SAMPLES;
		
		List<Double> best_frequencies = new ArrayList<Double>();
		List<Double> bestAmps = new ArrayList<Double>();
		
		for (int i = min_frequency_fft; i <= max_frequency_fft; i++) {

			final double current_frequency = i * 1.0 * RATE
					/ CHUNK_SIZE_IN_SAMPLES;
			final double draw_frequency = Math
					.round((current_frequency - MIN_FREQUENCY)
							/ DRAW_FREQUENCY_STEP)
					* DRAW_FREQUENCY_STEP + MIN_FREQUENCY;

			final double current_amplitude = Math.pow(data[i * 2], 2)
					+ Math.pow(data[i * 2 + 1], 2);
			
			final double normalized_amplitude = current_amplitude
					* Math.pow(MIN_FREQUENCY * MAX_FREQUENCY, 0.5)
					/ current_frequency;

			Double current_sum_for_this_slot = frequencies.get(draw_frequency);
			if (current_sum_for_this_slot == null) {
				current_sum_for_this_slot = 0.0;
			}

			frequencies.put(draw_frequency, Math.pow(current_amplitude, 0.5)
					/ draw_frequency_step + current_sum_for_this_slot);
			
			if (normalized_amplitude > best_amplitude) {
				best_frequency = current_frequency;
				best_amplitude = normalized_amplitude;
				
				best_frequencies.add(current_frequency);
				bestAmps.add(best_amplitude);
			}
			// test for harmonics
			// e.g. 220 is a harmonic of 110, so the harmonic factor is 2.0
			// and thus the decimal part is 0.0.
			//		230 isn't a harmonic of 110, the harmonic_factor would be 
			//		2.09 and 0.09 > 0.05
			//double harmonic_factor = current_frequency / best_frequency;
			//if ((best_amplitude == 0) || (harmonic_factor - Math.floor(harmonic_factor) > 0.05)) {
		}

		best_frequency = clusterFrequencies(best_frequencies, bestAmps);
		
		
		if (best_amplitude > noiseLevel) {
			pitchDetected = true;
		}
		
		if ( ! isNoiseInitialized) {
			// the first sample + 50% means we catch some jitter in the noise amplitude too.
			noiseLevel = best_amplitude * 5;
			isNoiseInitialized = true;
		}
		
		fr.bestFrequency = best_frequency;
		fr.frequencies = frequencies;
		fr.isPitchDetected = pitchDetected;
		fr.noiseLevel = noiseLevel;
		
		return fr;
	}

	public static double clusterFrequencies(List<Double> best_frequencies, List<Double> bestAmps) {

		List<FrequencyCluster> clusters = new ArrayList<FrequencyCluster>();
		FrequencyCluster currentCluster = new FrequencyCluster();
		clusters.add(currentCluster);
		
		
		if (best_frequencies.size() > 0)
		{
			currentCluster.add(best_frequencies.get(0), bestAmps.get(0));
		}
		
		// join clusters
		for(int i = 1; i < best_frequencies.size(); i++)
		{
			double freq = best_frequencies.get(i);
			double amp = bestAmps.get(i);
			
			if (currentCluster.isNear(freq)) {
				currentCluster.add(freq, amp);
				continue;
			}
			
			// this isn't near, and isn't harmonic, it's a different one.
			// NOTE: assuming harmonies are consecutive (no unharmonics in between harmonies)
			currentCluster = new FrequencyCluster();
			clusters.add(currentCluster);
			currentCluster.add(freq, amp);
		}
		
		// join harmonies
		FrequencyCluster nextCluster;
		for(int i = 1; i < clusters.size(); i ++) {
			currentCluster = clusters.get(i - 1);
			nextCluster = clusters.get(i);
			if (currentCluster.isHarmonic(nextCluster.average_frequency)) {
				currentCluster.total_amplitude += nextCluster.total_amplitude;
			}
		}
		
		// find best cluster
		double bestClusterAmplitude = 0;
		double bestFrequency = 0;
		for(int i = 0; i < clusters.size(); i ++) {
			FrequencyCluster clu = clusters.get(i);
			if (bestClusterAmplitude < clu.total_amplitude) {
				bestClusterAmplitude = clu.total_amplitude;
				bestFrequency = clu.average_frequency;
			}
		}
		
		return bestFrequency;
	}

	public static double distanceFromA4(double frequency) {
		return Math.log(frequency / 440) * 12 / Math.log(2);
	}
	
	public static String distanceFromA4ToNote(double distanceFromA4) {
		int noteIndex = (int) (Math.round(distanceFromA4) % 12);
		// 440 Hz is A4 and there are 9 half-steps from C4 to A4
		long octaveNumber = 4 + (long) Math.floor( (9.0 + Math.round(distanceFromA4)) / 12.0);
		if (noteIndex < 0) {
			// to avoid negative noteindex
			noteIndex += 12;
		}
		
		return notes[noteIndex] + octaveNumber;
	}
	
	public static String HzToNote(double frequency) {
		// distance in half-steps
		double distanceFromA4 = distanceFromA4(frequency);
		return distanceFromA4ToNote(distanceFromA4);
	}
}
