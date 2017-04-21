#!/usr/bin/python
"""
test_landmarks.py

Implementation of acoustic-landmark-based robust fingerprinting.
Port of the Matlab implementation.

2014-05-25 Dan Ellis dpwe@ee.columbia.edu
"""
from __future__ import print_function

# For reporting progress time
import time
# For command line interface
import docopt
import os
# For __main__
import sys
# For multiprocessing options
import multiprocessing  # for new/add
import joblib           # for match

# The actual analyzer class/code
import audfprint_analyze
# My hash_table implementation
import hash_table
# Access to match functions, used in command line interface
import xgboost as xgb
import numpy as np
import pandas as pd

def filename_list_iterator(filedir):
    """ Iterator to yeild all the filenames, possibly interpreting them
        as list files, prepending wavdir """
    if os.path.isdir(filedir):
        for filename in os.listdir(filedir):
            yield os.path.join(filedir, filename)

# for saving precomputed fprints
def ensure_dir(dirname):
    """ ensure that the named directory exists """
    if len(dirname):
        if not os.path.exists(dirname):
            try:
                os.makedirs(dirname)
            except:
                pass

# Command line interface

# basic operations, each in a separate function

def gen_samples_multiproc(analyzer, filename_iter, ncores,subsample=None):
    samplelist = joblib.Parallel(n_jobs=ncores)(joblib.delayed(gen_samples)(analyzer,filename,False,subsample) for filename in filename_iter)
    feats = np.concatenate([x[0] for x in samplelist], axis=0)
    probs = np.concatenate([x[1] for x in samplelist], axis=0)
    print("Generated " +  str(len(feats)) + " samples")
    return feats,probs

def gen_samples(analyzer, filename_iteri, iterFlag=True, subsample=None):

    # Adding files
    feats_list = []
    probs_list = []
    ix = 0
    if not iterFlag:
        filename = filename_iteri
        one_feats, one_probs = analyzer.wavfile2samples(filename, subsample=subsample)
        print(time.ctime() + " convert #" + str(ix) + ": " + filename + " ..., " + str(len(one_feats)) + " samples")
        return one_feats,one_probs
    for filename in filename_iter:
        one_feats, one_probs = analyzer.wavfile2samples(filename, subsample=subsample)
        feats_list.append(one_feats)
        probs_list.append(one_probs)
        print(time.ctime() + " convert #" + str(ix) + ": " + filename + " ..., " + str(len(one_feats)) + " samples")
        ix += 1
    feats = np.concatenate(feats_list, axis=0)
    probs = np.concatenate(probs_list, axis=0)
    print("Generated " +  str(len(feats)) + " samples")
    return feats,probs


# Command to separate out setting of analyzer parameters
def setup_analyzer(args):
    """Create a new analyzer object, taking values from docopts args"""
    # Create analyzer object; parameters will get set below
    analyzer = audfprint_analyze.Analyzer()
    # Read parameters from command line/docopts
    analyzer.density = float(args['--density'])
    analyzer.maxpksperframe = int(args['--pks-per-frame'])
    analyzer.maxpairsperpeak = int(args['--fanout'])
    analyzer.f_sd = float(args['--freq-sd'])
    analyzer.shifts = int(args['--shifts'])
    # fixed - 512 pt FFT with 256 pt hop at 11025 Hz
    analyzer.target_sr = int(args['--samplerate'])
    analyzer.n_fft = 512
    analyzer.n_hop = analyzer.n_fft/2
    # set default value for shifts depending on mode
    if analyzer.shifts == 0:
        # Default shift is 4 for match, otherwise 1
        analyzer.shifts = 1
    analyzer.fail_on_error = not args['--continue-on-error']
    return analyzer


# Command to construct the reporter object
def setup_reporter(args):
    """ Creates a logging function, either to stderr or file"""
    opfile = args['--opfile']
    if opfile and len(opfile):
        f = open(opfile, "w")
        def report(msglist):
            """Log messages to a particular output file"""
            for msg in msglist:
                f.write(msg+"\n")
    else:
        def report(msglist):
            """Log messages by printing to stdout"""
            for msg in msglist:
                print(msg)
    return report

# CLI specified via usage message thanks to docopt
USAGE = """
Landmark-based audio fingerprinting.
Create a new fingerprint dbase with "new",
append new files to an existing database with "add",
or identify noisy query excerpts with "match".
"precompute" writes a *.fpt file under precompdir
with precomputed fingerprint for each input wav file.
"merge" combines previously-created databases into
an existing database; "newmerge" combines existing
databases to create a new one.

Usage: audfprint [options]

Options:
  -t <dir>, --train <dir>         train set dir [default: ]     
  -e <dir>, --eval <dir>          eval set dir [default: ]     
  -n <dens>, --density <dens>     Target hashes per second [default: 20.0]
  -h <bits>, --hashbits <bits>    How many bits in each hash [default: 20]
  -b <val>, --bucketsize <val>    Number of entries per bucket [default: 100]
  -m <val>, --maxtime <val>       Largest time value stored [default: 16384]
  -u <val>, --maxtimebits <val>   maxtime as a number of bits (16384 == 14 bits)
  -r <val>, --samplerate <val>    Resample input files to this [default: 11025]
  -p <dir>, --precompdir <dir>    Save precomputed files under this dir [default: .]
  -i <val>, --shifts <val>        Use this many subframe shifts building fp [default: 0]
  -w <val>, --match-win <val>     Maximum tolerable frame skew to count as a match [default: 2]
  -N <val>, --min-count <val>     Minimum number of matching landmarks to count as a match [default: 5]
  -x <val>, --max-matches <val>   Maximum number of matches to report for each query [default: 1]
  -X, --exact-count               Flag to use more precise (but slower) match counting
  -R, --find-time-range           Report the time support of each match
  -Q, --time-quantile <val>       Quantile at extremes of time support [default: 0.05]
  -S <val>, --freq-sd <val>       Frequency peak spreading SD in bins [default: 30.0]
  -F <val>, --fanout <val>        Max number of hash pairs per peak [default: 10]
  -P <val>, --pks-per-frame <val>  Maximum number of peaks per frame [default: 10]
  -D <val>, --search-depth <val>  How far down to search raw matching track list [default: 100]
  -H <val>, --ncores <val>        Number of processes to use [default: 1]
  -o <name>, --opfile <name>      Write output (matches) to this file, not stdout [default: ]
  -K, --precompute-peaks          Precompute just landmarks (else full hashes)
  -k, --skip-existing             On precompute, skip items if output file already exists
  -C, --continue-on-error         Keep processing despite errors reading input
  -l, --list                      Input files are lists, not audio
  -s, --sortbytime                Sort multiple hits per file by time (instead of score)
  -v <val>, --verbose <val>       Verbosity level [default: 1]
  --version                       Report version number
  --help                          Print this message
"""

__version__ = 20150406

def main(argv):
    """ Main routine for the command-line interface to audfprint """
    # Other globals set from command line
    args = docopt.docopt(USAGE, version=__version__, argv=argv[1:])

    # Keep track of wall time
    initticks = time.clock()

    # Setup the analyzer if we're using one (i.e., unless "merge")
    #analyzer = setup_analyzer(args)
    analyzer = audfprint_analyze.Analyzer()



    cols = analyzer.gen_cols()
    #######################
    # Run the main commmand
    #######################

    ftrain = 'train.csv'
    feval = 'eval.csv'
    if args['--train'] and len(args['--train'])>0:
        train_iter = filename_list_iterator(args['--train'])
        eval_iter = filename_list_iterator(args['--eval'])
        # How many processors to use (multiprocessing)
        ncores = int(args['--ncores'])
        #feats_train,probs_train = gen_samples(analyzer, train_iter)
        #feats_eval,probs_eval = gen_samples(analyzer, eval_iter)
        feats_train,probs_train = gen_samples_multiproc(analyzer, train_iter, ncores, 2000)
        feats_eval,probs_eval = gen_samples_multiproc(analyzer, eval_iter, ncores)
        elapsedtime = time.clock() - initticks
        
        ptrain = pd.DataFrame(np.concatenate([feats_train,probs_train],axis=1), columns=(cols+['label']))
        peval = pd.DataFrame(np.concatenate([feats_eval,probs_eval],axis=1), columns=(cols+['label']))
        ptrain.to_csv(ftrain, index=False)
        peval.to_csv(feval, index=False)
    else:
        ptrain = pd.read_csv(ftrain, sep=",", engine='c')
        peval = pd.read_csv(feval, sep=",", engine='c')
    
    # train xgb
    xtrain = xgb.DMatrix(ptrain[cols], label=ptrain[['label']].values)
    xeval = xgb.DMatrix(peval[cols], label=peval[['label']].values)
    print(np.mean(xtrain.get_label()), len(xtrain.get_label()))
    print(np.mean(xeval.get_label()), len(xeval.get_label()))
    param = {'max_depth':5, 'eta':0.02, 'subsample':0.6, 'colsample_bytree':0.8, 'base_score':0.3, 'objective':'reg:linear', 'eval_metric':'rmse'}
    watchlist = [(xtrain, 'train'), (xeval, 'eval')]
    m_xgb = xgb.train(param, xtrain, 4000, watchlist, early_stopping_rounds=50)
    imp = m_xgb.get_fscore()
    imp = sorted(imp.items() , key = lambda d:d[1], reverse=True)
    print(imp)
    
    cols_imp = [x[0] for x in imp[0:100]]
    xtrain = xgb.DMatrix(ptrain[cols_imp], label=ptrain[['label']].values)
    xeval = xgb.DMatrix(peval[cols_imp], label=peval[['label']].values)
    watchlist = [(xtrain, 'train'), (xeval, 'eval')]
    m_xgb = xgb.train(param, xtrain, 4000, watchlist, early_stopping_rounds=50)

# Run the main function if called from the command line
if __name__ == "__main__":
    main(sys.argv)
