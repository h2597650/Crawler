"""
audfprint_analyze.py

Class to do the analysis of wave files into hash constellations.

2014-09-20 Dan Ellis dpwe@ee.columbia.edu
"""

from __future__ import print_function

import os
import numpy as np
import math
import scipy.signal

# For reading/writing hashes to file
import struct

# For glob2hashtable, localtester
import glob
import time

# For utility, glob2hashtable
import hash_table

import librosa

import audio_read

################ Globals ################
# Special extension indicating precomputed fingerprint
PRECOMPEXT = '.afpt'
# A different precomputed fingerprint is just the peaks
PRECOMPPKEXT = '.afpk'


def locmax(vec, indices=False):
    """ Return a boolean vector of which points in vec are local maxima.
        End points are peaks if larger than single neighbors.
        if indices=True, return the indices of the True values instead
        of the boolean vector.
    """
    # vec[-1]-1 means last value can be a peak
    #nbr = np.greater_equal(np.r_[vec, vec[-1]-1], np.r_[vec[0], vec])
    # the np.r_ was killing us, so try an optimization...
    nbr = np.zeros(len(vec)+1, dtype=bool)
    nbr[0] = True
    nbr[1:-1] = np.greater_equal(vec[1:], vec[:-1])
    maxmask = (nbr[:-1] & ~nbr[1:])
    if indices:
        return np.nonzero(maxmask)[0]
    else:
        return maxmask

def wgn(x, snr):
    x = np.array(x)
    snr = 10**(snr/10.0)
    xpower = np.sum(x**2)/len(x)
    npower = xpower / snr
    return x + np.random.randn(len(x)) * np.sqrt(npower)

def squares(sgram, px, py, idx=2):
    (Freq,Time) = np.shape(sgram)
    slen = idx*2+1
    ret = np.zeros((slen,slen))
    for off_x in range(-idx,idx+1):
        for off_y in range(-idx,idx+1):
            x = min(max(0,px+off_x), Freq-1)
            y = min(max(0,py+off_y), Time-1)
            ret[off_x][off_y] = sgram[x][y]
    ret = ret - sgram[px][py]
    return ret

def curvature(sq, pos):
    pa = np.array([-1,sq[pos[0]][pos[1]]])
    pb = np.array([0,0])
    pc = np.array([1,sq[-pos[0]][-pos[1]]])
    a = math.sqrt( np.power(pb-pc,2).sum() )
    b = math.sqrt( np.power(pa-pc,2).sum() )
    c = math.sqrt( np.power(pa-pb,2).sum() )
    S2 = (a+b+c)*(-a+b+c)*(a-b+c)*(a+b-c)
    return a*b*c/math.sqrt(S2)

def value_at(sgrams, px, py):
    px_low = int(math.floor(px))
    px_high = int(math.ceil(px))
    py_low = int(math.floor(py))
    py_high = int(math.ceil(py))
    v_low = sgrams[px_low][py_low] + (px-px_low) * (sgrams[px_high][py_low] - sgrams[px_low][py_low])
    v_high = sgrams[px_low][py_high] + (px-px_low) * (sgrams[px_high][py_high] - sgrams[px_low][py_high])
    value = v_low + (py-py_low) * (v_high-v_low)
    return value

# Constants for Analyzer
# DENSITY controls the density of landmarks found (approx DENSITY per sec)
DENSITY = 100.0 #100.0,20.0
# OVERSAMP > 1 tries to generate extra landmarks by decaying faster
OVERSAMP = 1
## 512 pt FFT @ 11025 Hz, 50% hop
#t_win = 0.0464
#t_hop = 0.0232
# Just specify n_fft
N_FFT = 2048 #2048,512
N_HOP = 256
# spectrogram enhancement
HPF_POLE = 0.98

# Globals defining packing of landmarks into hashes
F1_BITS = 10 #8
DF_BITS = 8 #6
DT_BITS = 7 #6
# derived constants
B1_MASK = (1 << F1_BITS) - 1
B1_SHIFT = DF_BITS + DT_BITS
DF_MASK = (1 << DF_BITS) - 1
DF_SHIFT = DT_BITS
DT_MASK = (1 << DT_BITS) - 1

def landmarks2hashes(landmarks):
    """Convert a list of (time, bin1, bin2, dtime) landmarks
    into a list of (time, hash) pairs where the hash combines
    the three remaining values.
    """
    # build up and return the list of hashed values
    return [(time_,
             (((bin1 & B1_MASK) << B1_SHIFT)
              | (((bin2 - bin1) & DF_MASK) << DF_SHIFT)
              | (dtime & DT_MASK)))
            for time_, bin1, bin2, dtime in landmarks]

def hashes2landmarks(hashes):
    """Convert the mashed-up landmarks in hashes back into a list
    of (time, bin1, bin2, dtime) tuples.
    """
    landmarks = []
    for time_, hash_ in hashes:
        dtime = hash_ & DT_MASK
        bin1 = (hash_ >> B1_SHIFT) & B1_MASK
        dbin = (hash_ >> DF_SHIFT) & DF_MASK
        # Sign extend frequency difference
        if dbin >= (1 << (DF_BITS-1)):
            dbin -= (1 << DF_BITS)
        landmarks.append((time_, bin1, bin1+dbin, dtime))
    return landmarks


class Analyzer(object):
    """ A class to wrap up all the parameters associated with
        the analysis of soundfiles into fingerprints """
    # Parameters

    # optimization: cache pre-calculated Gaussian profile
    __sp_width = None
    __sp_len = None
    __sp_vals = []

    def __init__(self, density=DENSITY):
        self.density = density
        self.target_sr = 8000
        self.n_fft = N_FFT
        self.n_hop = N_HOP
        self.shifts = 1
        # how wide to spreak peaks
        self.f_sd = 30.0
        # Maximum number of local maxima to keep per frame
        self.maxpksperframe = 10
        # Limit the num of pairs we'll make from each peak (Fanout)
        self.maxpairsperpeak = 10
        # Values controlling peaks2landmarks
        # +/- 31 bins in freq (LIMITED TO -32..31 IN LANDMARK2HASH)
        self.targetdf = 31
        # min time separation (traditionally 1, upped 2014-08-04)
        self.mindt = 2
        # max lookahead in time (LIMITED TO <64 IN LANDMARK2HASH)
        self.targetdt = 63
        # global stores duration of most recently-read soundfile
        self.soundfiledur = 0.0
        # .. and total amount of sound processed
        self.soundfiletotaldur = 0.0
        # .. and count of files
        self.soundfilecount = 0
        # Control behavior on file reading error
        self.fail_on_error = True

    def spreadpeaksinvector(self, vector, width=4.0):
        """ Create a blurred version of vector, where each of the local maxes
            is spread by a gaussian with SD <width>.
        """
        npts = len(vector)
        peaks = locmax(vector, indices=True)
        return self.spreadpeaks(zip(peaks, vector[peaks]),
                                npoints=npts, width=width)

    def spreadpeaks(self, peaks, npoints=None, width=4.0, base=None):
        """ Generate a vector consisting of the max of a set of Gaussian bumps
        :params:
          peaks : list
            list of (index, value) pairs giving the center point and height
            of each gaussian
          npoints : int
            the length of the output vector (needed if base not provided)
          width : float
            the half-width of the Gaussians to lay down at each point
          base : np.array
            optional initial lower bound to place Gaussians above
        :returns:
          vector : np.array(npoints)
            the maximum across all the scaled Gaussians
        """
        if base is None:
            vec = np.zeros(npoints)
        else:
            npoints = len(base)
            vec = np.copy(base)
        #binvals = np.arange(len(vec))
        #for pos, val in peaks:
        #   vec = np.maximum(vec, val*np.exp(-0.5*(((binvals - pos)
        #                                /float(width))**2)))
        if width != self.__sp_width or npoints != self.__sp_len:
            # Need to calculate new vector
            self.__sp_width = width
            self.__sp_len = npoints
            self.__sp_vals = np.exp(-0.5*((np.arange(-npoints, npoints+1)
                                           / float(width))**2))
        # Now the actual function
        for pos, val in peaks:
            vec = np.maximum(vec, val*self.__sp_vals[np.arange(npoints)
                                                     + npoints - pos])
        return vec

    def _decaying_threshold_fwd_prune(self, sgram, a_dec):
        """ forward pass of findpeaks
            initial threshold envelope based on peaks in first 10 frames
        """
        (srows, scols) = np.shape(sgram)
        sthresh = self.spreadpeaksinvector(
            np.max(sgram[:, :np.minimum(10, scols)], axis=1), self.f_sd
        )
        ## Store sthresh at each column, for debug
        #thr = np.zeros((srows, scols))
        peaks = np.zeros((srows, scols))
        # optimization of mask update
        __sp_pts = len(sthresh)
        __sp_v = self.__sp_vals

        for col in range(scols):
            s_col = sgram[:, col]
            # Find local magnitude peaks that are above threshold
            sdmaxposs = np.nonzero(locmax(s_col) * (s_col > sthresh))[0]
            # Work down list of peaks in order of their absolute value
            # above threshold
            valspeaks = sorted(zip(s_col[sdmaxposs], sdmaxposs), reverse=True)
            for val, peakpos in valspeaks[:self.maxpksperframe]:
                # What we actually want
                #sthresh = spreadpeaks([(peakpos, s_col[peakpos])],
                #                      base=sthresh, width=f_sd)
                # Optimization - inline the core function within spreadpeaks
                sthresh = np.maximum(sthresh,
                                     val*__sp_v[(__sp_pts - peakpos):
                                                (2*__sp_pts - peakpos)])
                peaks[peakpos, col] = 1
            sthresh *= a_dec
        return peaks

    def _decaying_threshold_bwd_prune_peaks(self, sgram, peaks, a_dec):
        """ backwards pass of findpeaks """
        scols = np.shape(sgram)[1]
        # Backwards filter to prune peaks
        sthresh = self.spreadpeaksinvector(sgram[:, -1], self.f_sd)
        for col in range(scols, 0, -1):
            pkposs = np.nonzero(peaks[:, col-1])[0]
            peakvals = sgram[pkposs, col-1]
            for val, peakpos in sorted(zip(peakvals, pkposs), reverse=True):
                if val >= sthresh[peakpos]:
                    # Setup the threshold
                    sthresh = self.spreadpeaks([(peakpos, val)], base=sthresh,
                                               width=self.f_sd)
                    # Delete any following peak (threshold should, but be sure)
                    if col < scols:
                        peaks[peakpos, col] = 0
                else:
                    # delete the peak
                    peaks[peakpos, col-1] = 0
            sthresh = a_dec*sthresh
        return peaks

    def find_peaks(self, d, sr):
        peaks,sgram,sgramo = self.find_peaks_sgram(d, sr)
        return peaks
    def find_peaks_sgram(self, d, sr):
        """ Find the local peaks in the spectrogram as basis for fingerprints.
            Returns a list of (time_frame, freq_bin) pairs.

        :params:
          d - np.array of float
            Input waveform as 1D vector

          sr - int
            Sampling rate of d (not used)

        :returns:
          pklist - list of (int, int)
            Ordered list of landmark peaks found in STFT.  First value of
            each pair is the time index (in STFT frames, i.e., units of
            n_hop/sr secs), second is the FFT bin (in units of sr/n_fft
            Hz).
        """
        if len(d) == 0:
            return []

        # masking envelope decay constant
        a_dec = (1.0 - 0.01*(self.density*np.sqrt(self.n_hop/352.8)/35.0)) \
                **(1.0/OVERSAMP)
        # Take spectrogram
        mywin = np.hanning(self.n_fft+2)[1:-1]
        sgramo = np.abs(librosa.stft(d, n_fft=self.n_fft,
                                    hop_length=self.n_hop,
                                    window=mywin))
        if np.max(sgramo) > 0.0:
            sgram = np.log(np.maximum(sgramo, np.max(sgramo)/1e6))
            sgram = sgram - np.mean(sgram)
        else:
            # The sgram is identically zero, i.e., the input signal was identically
            # zero.  Not good, but let's let it through for now.
            print("find_peaks: Warning: input signal is identically zero.")
        # High-pass filter onset emphasis
        # [:-1,] discards top bin (nyquist) of sgram so bins fit in 8 bits
        sgram = np.array([scipy.signal.lfilter([1, -1],
                                               [1, -(HPF_POLE)** \
                                                (1/OVERSAMP)], s_row)
                          for s_row in sgram])[:-1,]
        # Prune to keep only local maxima in spectrum that appear above an online,
        # decaying threshold
        peaks = self._decaying_threshold_fwd_prune(sgram, a_dec)
        # Further prune these peaks working backwards in time, to remove small peaks
        # that are closely followed by a large peak
        peaks = self._decaying_threshold_bwd_prune_peaks(sgram, peaks, a_dec)
        # build a list of peaks we ended up with
        scols = np.shape(sgram)[1]
        pklist = []
        for col in xrange(scols):
            for bin in np.nonzero(peaks[:, col])[0]:
                pklist.append( (col, bin) )
        return pklist, sgram, sgramo

    def peaks2landmarks(self, pklist):
        """ Take a list of local peaks in spectrogram
            and form them into pairs as landmarks.
            pklist is a column-sorted list of (col, bin) pairs as created
            by findpeaks().
            Return a list of (col, peak, peak2, col2-col) landmark descriptors.
        """
        # Form pairs of peaks into landmarks
        landmarks = []
        if len(pklist) > 0:
            # Find column of the final peak in the list
            scols = pklist[-1][0] + 1
            # Convert (col, bin) list into peaks_at[col] lists
            peaks_at = [[] for col in xrange(scols)]
            for (col, bin) in pklist:
                peaks_at[col].append(bin)

            # Build list of landmarks <starttime F1 endtime F2>
            for col in xrange(scols):
                for peak in peaks_at[col]:
                    pairsthispeak = 0
                    for col2 in xrange(col+self.mindt,
                                       min(scols, col+self.targetdt)):
                        if pairsthispeak < self.maxpairsperpeak:
                            for peak2 in peaks_at[col2]:
                                if abs(peak2-peak) < self.targetdf:
                                    #and abs(peak2-peak) + abs(col2-col) > 2 ):
                                    if pairsthispeak < self.maxpairsperpeak:
                                        # We have a pair!
                                        landmarks.append((col, peak,
                                                          peak2, col2-col))
                                        pairsthispeak += 1

        return landmarks

    def wavfile2peaks(self, filename, shifts=None):
        """ Read a soundfile and return its landmark peaks as a
            list of (time, bin) pairs.  If specified, resample to sr first.
            shifts > 1 causes hashes to be extracted from multiple shifts of
            waveform, to reduce frame effects.  """
        ext = os.path.splitext(filename)[1]
        if ext == PRECOMPPKEXT:
            # short-circuit - precomputed fingerprint file
            peaks = peaks_load(filename)
            dur = np.max(peaks, axis=0)[0]*self.n_hop/float(self.target_sr)
        else:
            try:
                #[d, sr] = librosa.load(filename, sr=self.target_sr)
                d, sr = audio_read.audio_read(filename, sr=self.target_sr, channels=1)
            except: # audioread.NoBackendError:
                message = "wavfile2peaks: Error reading " + filename
                if self.fail_on_error:
                  raise IOError(message)
                print(message, "skipping")
                d = []
                sr = self.target_sr
            # Store duration in a global because it's hard to handle
            dur = float(len(d))/sr
            if shifts is None or shifts < 2:
                peaks = self.find_peaks(d, sr);
            else:
                # Calculate hashes with optional part-frame shifts
                peaklists = []
                for shift in range(shifts):
                    shiftsamps = int(float(shift)/shifts*self.n_hop)
                    peaks,sgram,sgramo = self.find_peaks_sgram(d[shiftsamps:], sr)
                    shiftsgram = math.floor(len(d)/self.n_hop) - math.floor((len(d)-shiftsamps)/self.n_hop)
                    shiftsgram = int(shiftsgram)
                    for i in range(len(peaks)):
                        (t1,f1) = peaks[i]
                        peaks[i] = (t1+shiftsgram,f1)
                    peaklists.append(peaks)
                peaks = peaklists

        # instrumentation to track total amount of sound processed
        self.soundfiledur = dur
        self.soundfiletotaldur += dur
        self.soundfilecount += 1
        return peaks

    def wavfile2hashes(self, filename):
        """ Read a soundfile and return its fingerprint hashes as a
            list of (time, hash) pairs.  If specified, resample to sr first.
            shifts > 1 causes hashes to be extracted from multiple shifts of
            waveform, to reduce frame effects.  """
        ext = os.path.splitext(filename)[1]
        if ext == PRECOMPEXT:
            # short-circuit - precomputed fingerprint file
            hashes = hashes_load(filename)
            dur = np.max(hashes, axis=0)[0]*self.n_hop/float(self.target_sr)
            # instrumentation to track total amount of sound processed
            self.soundfiledur = dur
            self.soundfiletotaldur += dur
            self.soundfilecount += 1
        else:
            peaks = self.wavfile2peaks(filename, self.shifts)
            if len(peaks) == 0:
              return []
            # Did we get returned a list of lists of peaks due to shift?
            if isinstance(peaks[0], list):
                peaklists = peaks
                query_hashes = []
                for peaklist in peaklists:
                    query_hashes += landmarks2hashes(
                        self.peaks2landmarks(peaklist)
                    )
            else:
                query_hashes = landmarks2hashes(self.peaks2landmarks(peaks))

            # remove duplicate elements by pushing through a set
            hashes = sorted(list(set(query_hashes)))

        #print("wavfile2hashes: read", len(hashes), "hashes from", filename)
        return hashes
    
    def wavfile2samples(self, filename, label=True, subsample=None):
        landmarks = self.peaks2landmarks(self.wavfile2peaks(filename))
        d, sr = audio_read.audio_read(filename, sr=self.target_sr, channels=1)
        peaks,sgram,sgramo = self.find_peaks_sgram(d, sr)
        if subsample and subsample<len(landmarks):
            index = np.random.choice(len(landmarks), subsample, replace=False)
            landmarks = [ landmarks[idx] for idx in index]
        lms_map = {}
        for lm in landmarks:
            lms_map[lm] = 0.0

        # probs
        probs = np.zeros((len(landmarks),1))
        if label:
            test_cnt = 0.0
            # move a slide
            peaklist = self.wavfile2peaks(filename, 20)
            peaklist = peaklist[3:17]
            # test with wgn
            for db in range(20,41):
                test_d = wgn(d, db)
                peaklist.append(self.find_peaks(test_d, sr))
            for idx in range(len(peaklist)):
                test_cnt += 1.0
                lms_test = self.peaks2landmarks(peaklist[idx])
                for (t1,f1,f2,dt) in lms_test:
                    for t in range(t1,t1+1):
                        key = (t,f1,f2,dt)
                        if key in lms_map:
                            lms_map[key] += 1.0
                            break
            for idx, key in enumerate(landmarks):
                probs[idx] = lms_map[key] / test_cnt
        
        # features
        feats_list = []
        (Freq,Time) = np.shape(sgram)
        for idx in range(len(landmarks)):
            (Freq,Time) = (float(Freq),float(Time))
            (t1,f1,f2,dt) = landmarks[idx]
            t2 = t1 + dt
            # make sure f1 < f2
            if f1 > f2 or (f1==f2 and t1 > t2):
                f1,f2 = f2,f1
                t1,t2 = t2,t1
            feats_1 = [t1, t2, f1, f2, t2-t1, f2-f1]
            # ratio
            feats_2 = [t1/Time, t2/Time, f1/Freq, f2/Freq, (t2-t1)/Time, (f2-f1)/Freq]
            # distance
            dist = [math.sqrt(feats_1[4]**2+feats_2[5]**2), math.sqrt(feats_2[4]**2+feats_2[5]**2)]
            # energy
            feats_e = [sgram[f1][t1], sgram[f2][t2]]
            feats_e.extend([feats_e[0]+feats_e[1], feats_e[0]*feats_e[1]])
            feats_e.extend([(feats_e[1]-feats_e[2]), (feats_e[1]-feats_e[2])/dist[0], (feats_e[1]-feats_e[2])/dist[1]])
            feats_eo = [sgramo[f1][t1], sgramo[f2][t2]]
            feats_eo.extend([feats_eo[0]+feats_eo[1], feats_eo[0]*feats_eo[1]])
            feats_eo.extend([(feats_eo[1]-feats_eo[2]), (feats_eo[1]-feats_eo[2])/dist[0], (feats_eo[1]-feats_eo[2])/dist[1]])
            # distance
            dist += [math.sqrt(feats_1[4]**2+feats_2[5]**2+(feats_eo[0]-feats_eo[1])**2)]
            dist += [math.sqrt(feats_2[4]**2+feats_2[5]**2+(feats_e[0]-feats_e[1])**2)]
            # engery surrounding
            locs = [(-1,1),(0,1),(1,1),(-1,0),(1,0),(-1,-1),(0,-1),(1,-1)]
            poss = [(-1,1),(0,1),(1,1),(1,0)]
            feats_surs = []
            sgrams = [sgram,sgramo]
            fts = [(f1, t1), (f2, t2)]
            for (fi,ti) in fts:
                for sgrami in sgrams:
                    sq_i = squares(sgrami, fi, ti, 2)
                    feats_sur_i = np.concatenate(sq_i.tolist()).tolist()
                    feats_sur_i.extend([sq_i[2*loc[0]][2*loc[1]]-2*sq_i[loc[0]][loc[1]] for loc in locs])
                    feats_sur_i.extend([curvature(sq_i,pos) for pos in poss])
                    feats_surs.append(feats_sur_i)
            # delta E / delta x
            feats_delta = [ (feats_e[1]-feats_e[0])/(f2-f1+0.1), (feats_e[1]-feats_e[0])/(t2-t1+0.1), (t2-t1)/(f2-f1+0.1) ]
            feats_delta.extend([ (feats_eo[1]-feats_eo[0])/(f2-f1+0.1), (feats_eo[1]-feats_eo[0])/(t2-t1+0.1) ])
            # Freq*Energy
            feats_fe_1 = [feats_eo[0]*f1, feats_eo[0]*math.log1p(f1), math.log1p(feats_eo[0])*f1]
            feats_fe_2 = [feats_eo[1]*f2, feats_eo[1]*math.log1p(f2), math.log1p(feats_eo[1])*f2]
            feats_fe_12 = (np.array(feats_fe_1)*np.array(feats_fe_2)).tolist()
            feats_fe = feats_fe_1 + feats_fe_2 + feats_fe_12
            # line points
            line = [ ( f1+(f2-f1)*i/10.0, t1+(t2-t1)*i/10.0 ) for i in range(1,10)]
            line_values = [ value_at(sgram,p[0],p[1]) for p in line]
            line_valueso = [ value_at(sgramo,p[0],p[1]) for p in line]
            feats_line = line_values + line_valueso + [np.mean(line_values),np.std(line_values),np.mean(line_valueso),np.std(line_valueso)]
            # square points
            '''
            square_line = []
            for i in range(1,6):
                for j in range(1,6):
                    square_line.append( (f1+(f2-f1)*i/6.0, t1+(t2-t1)*i/6.0) )
            sql_values = [ value_at(sgram,p[0],p[1]) for p in square_line]
            sql_valueso = [ value_at(sgramo,p[0],p[1]) for p in square_line]
            feats_sql = sql_values + sql_valueso + [np.mean(sql_values),np.std(sql_values),np.mean(sql_valueso),np.std(sql_valueso)]
            '''
            # append to feats
            feats = [Time,Freq] 
            feats.extend(feats_1)
            feats.extend(feats_2)
            feats.extend(dist)
            feats.extend(feats_e)
            feats.extend(feats_eo)
            for feats_sur_i in feats_surs:
                feats.extend(feats_sur_i)
            feats.extend(feats_delta)
            feats.extend(feats_fe)
            feats.extend(feats_line)
            #feats.extend(feats_sql)
            feats_list.append(feats)
        return np.array(feats_list), probs
    
    def gen_cols(self):
        cols = ['Time', 'Freq']
        cols += ['t1', 't2', 'f1', 'f2', 'dt', 'df']
        cols += ['rt1', 'rt2', 'rf1', 'rf2', 'rdt', 'rdf']
        cols += ['dist_ft', 'dist_rft', 'dist_fte', 'dist_rfte']
        cols += ['e1', 'e2', 'e1+e2', 'e1*e2', 'e1-e2', '(e1-e2)/dist', '(e1-e2)/rdist']
        cols += ['eo1', 'eo2', 'eo1+eo2', 'eo1*eo2', 'eo1-eo2', '(eo1-eo2)/dist', '(eo1-eo2)/rdist']

        locs = [(-1,1),(0,1),(1,1),(-1,0),(1,0),(-1,-1),(0,-1),(1,-1)]
        locs = [ (str(x[0]),str(x[1])) for x in locs]
        poss = [(-1,1),(0,1),(1,1),(1,0)]
        poss = [ (str(x[0]),str(x[1])) for x in poss]
        sgrams = ['e','eo']
        fts = [('f1', 't1'), ('f2', 't2')]
        for (fi,ti) in fts:
            for ei in sgrams:
                cols += [ '_'.join([ei,fi,ti,str(x)]) for x in range(0,25)]
                cols += [ '_'.join([ei,fi,ti,'hess',loc[0],loc[1]]) for loc in locs]
                cols += [ '_'.join([ei,fi,ti,'curve',pos[0],pos[1]]) for pos in poss]
        cols += ['de/df', 'de/dt', 'dt/de', 'rde/df', 'rde/dt']
        eof1 = ['eo1*f1', 'eo1*log(f1)', 'log(eo1)*f1']
        eof2 = ['eo2*f2', 'eo2*log(f2)', 'log(eo2)*f2']
        eof12 = [ eof1[i]+eof2[i] for i in range(len(eof1))]
        cols += (eof1 + eof2 + eof12)
        line = [ 'line_'+str(i/10.0) for i in range(1,10)]
        lineo = [ 'lineo_'+str(i/10.0) for i in range(1,10)]
        cols += (line + lineo + ['line_mean', 'line_std','lineo_mean', 'lineo_std'])
        sql = [ 'sql_'+str(i) for i in range(25)]
        sqlo = [ 'sqlo_'+str(i) for i in range(25)]
        #cols += (sql + sqlo + ['sql_mean', 'sql_std', 'sqlo_mean', 'sqlo_std'])
        return cols
    ########### functions to link to actual hash table index database #######

    def ingest(self, hashtable, filename):
        """ Read an audio file and add it to the database
        :params:
          hashtable : HashTable object
            the hash table to add to
          filename : str
            name of the soundfile to add
        :returns:
          dur : float
            the duration of the track
          nhashes : int
            the number of hashes it mapped into
        """
        #sr = 11025
        #print("ingest: sr=",sr)
        #d, sr = librosa.load(filename, sr=sr)
        # librosa.load on mp3 files prepends 396 samples compared
        # to Matlab audioread ??
        #hashes = landmarks2hashes(peaks2landmarks(find_peaks(d, sr,
        #                                                     density=density,
        #                                                     n_fft=n_fft,
        #                                                     n_hop=n_hop)))
        hashes = self.wavfile2hashes(filename)
        hashtable.store(filename, hashes)
        #return (len(d)/float(sr), len(hashes))
        #return (np.max(hashes, axis=0)[0]*n_hop/float(sr), len(hashes))
        # soundfiledur is set up in wavfile2hashes, use result here
        return self.soundfiledur, len(hashes)




########### functions to read/write hashes to file for a single track #####

# Format string for writing binary data to file
HASH_FMT = '<2i'
HASH_MAGIC = 'audfprinthashV00'  # 16 chars, FWIW
PEAK_FMT = '<2i'
PEAK_MAGIC = 'audfprintpeakV00'  # 16 chars, FWIW

def hashes_save(hashfilename, hashes):
    """ Write out a list of (time, hash) pairs as 32 bit ints """
    with open(hashfilename, 'wb') as f:
        f.write(HASH_MAGIC)
        for time_, hash_ in hashes:
            f.write(struct.pack(HASH_FMT, time_, hash_))

def hashes_load(hashfilename):
    """ Read back a set of hashes written by hashes_save """
    hashes = []
    fmtsize = struct.calcsize(HASH_FMT)
    with open(hashfilename, 'rb') as f:
        magic = f.read(len(HASH_MAGIC))
        if magic != HASH_MAGIC:
            raise IOError('%s is not a hash file (magic %s)'
                          % (hashfilename, magic))
        data = f.read(fmtsize)
        while data is not None and len(data) == fmtsize:
            hashes.append(struct.unpack(HASH_FMT, data))
            data = f.read(fmtsize)
    return hashes

def peaks_save(peakfilename, peaks):
    """ Write out a list of (time, bin) pairs as 32 bit ints """
    with open(peakfilename, 'wb') as f:
        f.write(PEAK_MAGIC)
        for time_, bin_ in peaks:
            f.write(struct.pack(PEAK_FMT, time_, bin_))

def peaks_load(peakfilename):
    """ Read back a set of (time, bin) pairs written by peaks_save """
    peaks = []
    fmtsize = struct.calcsize(PEAK_FMT)
    with open(peakfilename, 'rb') as f:
        magic = f.read(len(PEAK_MAGIC))
        if magic != PEAK_MAGIC:
            raise IOError('%s is not a peak file (magic %s)'
                          % (peakfilename, magic))
        data = f.read(fmtsize)
        while data is not None and len(data) == fmtsize:
            peaks.append(struct.unpack(PEAK_FMT, data))
            data = f.read(fmtsize)
    return peaks

######## function signature for Gordon feature extraction
######## which stores the precalculated hashes for each track separately

extract_features_analyzer = None

def extract_features(track_obj, *args, **kwargs):
    """ Extract the audfprint fingerprint hashes for one file.
    :params:
      track_obj : object
        Gordon's internal structure defining a track; we use
        track_obj.fn_audio to find the actual audio file.
    :returns:
      hashes : list of (int, int)
        The times (in frames) and hashes analyzed from the audio file.
    """
    global extract_features_analyzer
    if extract_features_analyzer == None:
        extract_features_analyzer = Analyzer()

    density = None
    n_fft = None
    n_hop = None
    sr = None
    if "density" in kwargs:
        density = kwargs["density"]
    if "n_fft" in kwargs:
        n_fft = kwargs["n_fft"]
    if "n_hop" in kwargs:
        n_hop = kwargs["n_hop"]
    if "sr" in kwargs:
        sr = kwargs["sr"]
    extract_features_analyzer.density = density
    extract_features_analyzer.n_fft = n_fft
    extract_features_analyzer.n_hop = n_hop
    extract_features_analyzer.target_sr = sr
    return extract_features_analyzer.wavfile2hashes(track_obj.fn_audio)


# Handy function to build a new hash table from a file glob pattern
g2h_analyzer = None

def glob2hashtable(pattern, density=20.0):
    """ Build a hash table from the files matching a glob pattern """
    global g2h_analyzer
    if g2h_analyzer == None:
        g2h_analyzer = Analyzer(density=density)

    ht = hash_table.HashTable()
    filelist = glob.glob(pattern)
    initticks = time.clock()
    totdur = 0.0
    tothashes = 0
    for ix, file_ in enumerate(filelist):
        print(time.ctime(), "ingesting #", ix, ":", file_, "...")
        dur, nhash = g2h_analyzer.ingest(ht, file_)
        totdur += dur
        tothashes += nhash
    elapsedtime = time.clock() - initticks
    print("Added", tothashes, "(", tothashes/float(totdur), "hashes/sec) at ",
          elapsedtime/totdur, "x RT")
    return ht

def local_tester():
    test_fn = '/Users/dpwe/Downloads/carol11k.wav'
    test_ht = hash_table.HashTable()
    test_analyzer = Analyzer()

    test_analyzer.ingest(test_ht, test_fn)
    test_ht.save('httest.pklz')

# Run the test function if called from the command line
if __name__ == "__main__":
    local_tester()
