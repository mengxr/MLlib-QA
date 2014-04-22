#!/usr/bin/env bash

THIS_DIR=$(cd "$(dirname "$0")"; pwd)
DATA_DIR=$THIS_DIR/../data

mkdir -p $DATA_DIR
cd $DATA_DIR

wget -N http://www.csie.ntu.edu.tw/~cjlin/libsvmtools/datasets/binary/rcv1_test.binary.bz2
bunzip2 rcv1_test.binary.bz2
