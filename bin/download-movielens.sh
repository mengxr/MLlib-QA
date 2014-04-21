#!/usr/bin/env bash

THIS_DIR=$(cd "$(dirname "$0")"; pwd)
DATA_DIR=$THIS_DIR/../data

mkdir -p $DATA_DIR
cd $DATA_DIR

wget -N http://files.grouplens.org/datasets/movielens/ml-10m.zip
unzip -o ml-10m.zip
