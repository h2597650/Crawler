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

np.random.seed(1337)
from keras.models import Sequential
from keras.layers.core import Dense, Activation
from keras.optimizers import SGD, Adadelta, Adagrad
from keras.layers.advanced_activations import LeakyReLU
from keras.initializers import Constant

import multiprocessing


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



def extract_landmarks(analyzer, m_xgb, mp3_iter, dest_folder, cols, ncores):
    ensure_dir(dest_folder)
    
    lock = multiprocessing.Manager().Lock()
    pool = multiprocessing.Manager().Pool(processes = ncores)
    for filename in mp3_iter:
        pool.apply_async(gen_hashes, (lock,analyzer,filename,m_xgb,dest_folder,cols, ))
    pool.close()
    pool.join()
    return True
    '''
    retList = joblib.Parallel(n_jobs=ncores)(joblib.delayed(gen_hashes)(lock,analyzer,filename,m_xgb,dest_folder,cols) for filename in mp3_iter)
    for x in retList:
        if not x:
            return False
    return True
    ''' 


def gen_hashes(lock, analyzer, filename, m_xgb, dest_folder, cols):
    one_feats, one_probs = analyzer.wavfile2samples(filename, label=False)
    #print(time.ctime() + " extract #" + ": " + filename + " ..., " + str(len(one_feats)) + " feats")
    xprds = xgb.DMatrix(one_feats, feature_names=cols)
    with lock:
        prds = m_xgb.predict(xprds)
    #print(time.ctime() + " predict #" + ": " + filename)
    landmarks = [(int(f[2]), int(f[4]), int(f[5]), int(f[6])) for f in one_feats]
    prds = zip(prds, landmarks)
    prds = sorted(prds, key=lambda d:d[0], reverse=True)
    landmarks = [x[1] for x in prds]
    hashes = audfprint_analyze.landmarks2hashes(landmarks)
    mp3_name = os.path.basename(filename).split('.')[0]
    hash_name = os.path.join(dest_folder, mp3_name+'.lmfp')
    with open(hash_name, 'w') as hash_f:
        for (time_, hash_) in hashes:
            hash_f.write(str(hash_) + '\t' + str(time_) + '\n')
        hash_f.flush()
    print(time.ctime() + " save #" + ": " + filename + " " + str(len(one_feats)) + " hashes")
    return True

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
  -f <dir>, --file <dir>          files dir [default: ]     
  -d <dir>, --dest <dir>          extract lanmarkds dir [default: ]     
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
    ncores = int(args['--ncores'])
    if args['--train'] and len(args['--train'])>0:
        train_iter = filename_list_iterator(args['--train'])
        eval_iter = filename_list_iterator(args['--eval'])
        # How many processors to use (multiprocessing)
        #feats_train,probs_train = gen_samples(analyzer, train_iter)
        #feats_eval,probs_eval = gen_samples(analyzer, eval_iter)
        feats_train,probs_train = gen_samples_multiproc(analyzer, train_iter, ncores, 5000)
        feats_eval,probs_eval = gen_samples_multiproc(analyzer, eval_iter, ncores, 5000)
        
        ptrain = pd.DataFrame(np.concatenate([feats_train,probs_train],axis=1), columns=(cols+['label']))
        peval = pd.DataFrame(np.concatenate([feats_eval,probs_eval],axis=1), columns=(cols+['label']))
        ptrain.to_csv(ftrain, index=False)
        peval.to_csv(feval, index=False)
    else:
        ptrain = pd.read_csv(ftrain, sep=",", engine='c')
        peval = pd.read_csv(feval, sep=",", engine='c')
    m_xgb = train_xgb(ptrain,peval,cols)
    m_xgb.save_model('model.xgb')
    if args['--file'] and len(args['--file'])>0:
        mp3_iter = filename_list_iterator(args['--file'])
        extract_landmarks(analyzer, m_xgb, mp3_iter, args['--dest'], cols, ncores)
    #train_keras(ptrain,peval,cols)



def normalize(ptrain,peval,cols):
    train_x,eval_x = ptrain[cols],peval[cols]
    train_y,eval_y = ptrain[['label']],peval[['label']]
    all_x = pd.concat([train_x,eval_x])
    dmin, dmax = all_x.min(axis=0), all_x.max(axis=0)
    train_x = (train_x - dmin) / (dmax - dmin + 0.1)
    eval_x = (eval_x - dmin) / (dmax - dmin + 0.1)
    return train_x.values,eval_x.values,train_y.values,eval_y.values
def train_keras(ptrain,peval,cols):
    train_x,eval_x,train_y,eval_y = normalize(ptrain,peval,cols)
    print(train_x.shape, eval_x.shape)
    print(train_y.shape, eval_y.shape)
    model = Sequential()
    model.add(Dense(100, kernel_initializer='uniform', input_dim=train_x.shape[1]))
    #model.add(Activation(LeakyReLU(0.1)))
    model.add(Activation('sigmoid'))

    model.add(Dense(100))
    model.add(Activation(LeakyReLU(0.1)))
    
    model.add(Dense(100))
    model.add(Activation(LeakyReLU(0.1)))
    
    model.add(Dense(1))

    sgd = SGD(lr=0.01, decay=1e-10, momentum=0.9, nesterov=True)
    adadelta = Adadelta(lr=1.0, rho=0.95, epsilon=1e-06)
    model.compile(loss='mse', optimizer=adadelta)
    hist = model.fit(train_x, train_y, batch_size=128, epochs=5000, shuffle=True, verbose=2, validation_data=(eval_x,eval_y))
    score = model.evaluate(eval_x, eval_y, batch_size=100)
    print('eval final rmae : ', score)

def train_xgb(ptrain,peval,cols):
    # train xgb
    '''
    probs_norm = ptrain[['label']].values + 0.1
    probs_norm = probs_norm.transpose()[0]
    probs_norm = probs_norm / probs_norm.sum()
    sample_index = np.random.choice(len(probs_norm), int(0.2*len(probs_norm)), replace=False, p=probs_norm)
    ptrain = ptrain.iloc[sample_index]
    pevals = peval[peval.label>0.3]
    '''
    pevals = peval
    xtrain = xgb.DMatrix(ptrain[cols], label=ptrain[['label']].values)
    xevals = xgb.DMatrix(pevals[cols], label=pevals[['label']].values)
    xeval = xgb.DMatrix(peval[cols], label=peval[['label']].values)
    print(np.mean(xtrain.get_label()), len(xtrain.get_label()))
    print(np.mean(xeval.get_label()), len(xeval.get_label()))
    param = {'max_depth':5, 'eta':0.02, 'subsample':0.6, 'colsample_bytree':0.8, 'base_score':0.3, 'objective':'reg:linear', 'eval_metric':'rmse'}
    watchlist = [(xtrain, 'train'), (xevals, 'eval')]
    m_xgb = xgb.train(param, xtrain, 3000, watchlist, early_stopping_rounds=50)
    imp = m_xgb.get_fscore()
    imp = sorted(imp.items() , key = lambda d:d[1], reverse=True)
    print(imp)
    prds = m_xgb.predict(xeval)
    prds = zip(prds,peval[['label']].values.transpose()[0])
    prds = sorted(prds, key=lambda d:d[0], reverse=True)
    print('score :', np.mean([x[1] for x in prds[0:int(0.2*len(prds))]]))

    #cols_imp = [x[0] for x in imp[0:100]]
    #watchlist = [(xtrain, 'train'), (xeval, 'evals')]
    #m_xgb = xgb.train(param, xtrain, 4000, watchlist, early_stopping_rounds=50)
    return m_xgb

# Run the main function if called from the command line
if __name__ == "__main__":
    main(sys.argv)
