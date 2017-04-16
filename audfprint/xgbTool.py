import pandas as pd
import numpy as np
import xgboost as xgb
import time,sys,math

class XgbTool:
    def __init__(self):
        pass
    # feats, label, ids
    def parse_cols(self, fcsv, label_cols, id_cols):
        with open(fcsv, "r") as fin:
            columns = fin.readline().split(',')
            feats_idxs = []
            label_idx = 0
            id_idxs = []
            for idx,col in enumerate(columns):
                if col in label_cols:
                    id_idxs.append(idx)
                elif col in id_cols:
                    id_idxs = idx
                else:
                    feats_idxs.append(idx)
            return feats_idxs,label_idx,id_idxs

    def gen_feats_cols(self, cols, label_cols, id_cols):
        feats_cols = []
        for col in list(cols):
            if col not in label_cols+id_cols:
                feats_cols.append(col)
        return feats_cols
            
    def tune_CV(self, ftrain, fmodel, logfile, num_round, nfold, label_cols=['label'], id_cols=['dist_id','minute']):
        ptrain = pd.read_csv(ftrain, sep=",")
        print "load csv complete"
        feats_cols = self.gen_feats_cols(ptrain.columns, label_cols, id_cols)
        xtrain = xgb.DMatrix(ptrain[feats_cols], label=ptrain[label_cols].values)
        print np.sum(ptrain[label_cols].values), len(ptrain[label_cols].values)
        ptrain = None
        print "DMatrix complete"

        logout = open(logfile, "w")
        g_best_m = {'score':0}
        for dep in range(5,6):
            for eta in range(30,0,-1):
                for rowsample in range(60,71,10):
                    for colsample in range(60,71,10):
                        #for obj in ['reg:linear']:
                        for obj in ['binary:logistic']:
                            adj_param = {'max_depth':dep, 'eta':eta/100.0, 'subsample':rowsample/100.0,\
                                        'colsample_bytree':colsample/100.0, 'objective':obj, \
                                        'eval_metric':'auc', 'silent':1, 'tree_method':'exact'}
                            start_time = time.time()
                            cv_xgb = xgb.cv(adj_param, xtrain, num_round, nfold, early_stopping_rounds=30, verbose_eval=20)
                            best_m = [0,0]  
                            cv_score = cv_xgb.values[:,0]
                            for i in range(0,len(cv_score)):
                                auc_t = cv_score[i]
                                if(auc_t>best_m[1]):
                                    best_m[0] = i+1
                                    best_m[1] = auc_t
                            print 'use time' , time.time() - start_time , 's'
                            print "dep:%d, eta:%.2lf, rows:%.2lf, cols:%.2lf, obj:%s, tree:%d, score:%lf" % \
                                    (dep, eta/100.0, rowsample/100.0, colsample/100.0, obj, best_m[0], best_m[1])
                            logout.write("dep:%d, eta:%.2lf, rows:%.2lf, cols:%.2lf, obj:%s, tree:%d, score:%lf\n" % \
                                    (dep, eta/100.0, rowsample/100.0, colsample/100.0, obj, best_m[0], best_m[1]))
                            logout.flush()
                            if best_m[1] > g_best_m['score']:
                                g_best_m = adj_param
                                g_best_m['tree'] = best_m[0]
                                g_best_m['score'] = best_m[1]
        logout.close()
        modelout = open(fmodel, "w")
        for k,v in g_best_m.items():
            modelout.write("%s#%s\n" % (k, str(v)) )
        modelout.close()

    def predict(self, ftrain, ftest, fmodel, fout, fsubmit=None, foutsub=None, label_cols=['label'], id_cols=['dist_id','minute']):
        ptrain = pd.read_csv(ftrain, sep=",", engine='c')
        print "load csv complete"
        feats_cols = self.gen_feats_cols(ptrain.columns, label_cols, id_cols)
        xtrain = xgb.DMatrix(ptrain[feats_cols], label = ptrain[label_cols].values)
        print np.sum(xtrain.get_label()), len(xtrain.get_label())
        ptrain = None
        print "DMatrix complete"
        
        ptest = pd.read_csv(ftest, sep=",")
        print "load test csv complete"
        feats_cols = self.gen_feats_cols(ptest.columns, label_cols, id_cols)
        xtest = xgb.DMatrix(ptest[feats_cols], label = ptest[label_cols].values)
        id_list = ptest[id_cols].values
        last_labels = ptest.last_label.values
        print np.mean(xtest.get_label())
        ptest = None
        print "DMatrix test complete"
        
        modelin = open(fmodel, "r")
        param = {}
        tree_num = 0
        int_attr = ['max_depth', 'silent']
        double_attr = ['eta', 'subsample', 'colsample_bytree', 'base_score']
        str_attr = ['objective', 'eval_metric', 'tree_method']
        for line in modelin:
            k,v = line.strip().split('#')
            if k in int_attr:
                param[k] = int(v)
            elif k in double_attr:
                param[k] = float(v)
            elif k in str_attr:
                param[k] = v
            if k=='tree':
                tree_num = int(v)
        param['silent'] = 0
        watchlist = [(xtrain, 'train'), (xtest, 'eval')]
        m_xgb = xgb.train(param, xtrain, tree_num, watchlist, mae_exp, early_stopping_rounds=50)
        imp = m_xgb.get_fscore()
        print sorted(imp.items() , key = lambda d:d[1], reverse=True)

        ''' 
        prd_test = m_xgb.predict(xtest)
        prd_test = (1+prd_test)*last_labels
        real_test = (1+xtest.get_label())*last_labels
        print "test map : %lf" % np.mean(np.abs(prd_test-real_test))
        '''
        prd_test = m_xgb.predict(xtest)
        with open(fout, "w") as out:
            out.write("%s,%s\n" % (",".join(id_cols), "score"))
            for i in range(id_list.shape[0]):
                out.write("%s,%lf\n" % (",".join([ str(x) for x in id_list[i] ]), prd_test[i]) )
        
        if fsubmit is not None:
            psubmit = pd.read_csv(fsubmit, sep=",")
            print "load submit csv complete"
            feats_cols = self.gen_feats_cols(psubmit.columns, label_cols, id_cols)
            xsubmit = xgb.DMatrix(psubmit[feats_cols])
            id_list = psubmit[id_cols].values
            psubmit = None
            print "DMatrix submit complete"

            prd_submit = m_xgb.predict(xsubmit)
            with open(foutsub, 'w') as out:
                out.write("%s,%s\n" % (",".join(id_cols), "score"))
                for i in range(id_list.shape[0]):
                    out.write("%s,%lf\n" % (",".join([ str(x) for x in id_list[i] ]), prd_submit[i]) )



if __name__ == '__main__':
    xgbTool = XgbTool()
    
    if len(sys.argv)==0:
        print "python xgbTool.py train test model prd"
        exit()
    flag = int(sys.argv[1])
    ftrain = sys.argv[2]
    ftest = sys.argv[3]
    fmodel = sys.argv[4]
    fprd = sys.argv[5]
    fsubmit = sys.argv[6]
    foutsub = sys.argv[7]
    if flag==0:
        xgbTool.predict(ftrain, ftest, fmodel, fprd, fsubmit=fsubmit, foutsub=foutsub)
    else:
        xgbTool.tune_CV(ftrain, fmodel, "../log/parameter.log", 3000, 3)
        #xgbTool.predict(ftrain, ftest, fmodel, fprd)


