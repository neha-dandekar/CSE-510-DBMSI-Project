package iterator;

import java.util.Vector;
import java.util.List;
import java.io.IOException;

import bufmgr.PageNotReadException;

import global.AttrType;
import global.IndexType;
import global.PageId;
import global.GlobalConst;
import global.RID;
import heap.Heapfile;
import heap.InvalidTupleSizeException;
import heap.InvalidTypeException;
import heap.Tuple;
import heap.Scan;
import heap.InvalidSlotNumberException;
import heap.FileAlreadyDeletedException;
import heap.HFBufMgrException;
import index.IndexException;
import index.IndexScan;
import index.UnknownIndexTypeException;


public class BTreeSky extends Iterator{



//Operations Buffer
        private byte[][] buffer;
//SkyLine buffer
        private OBuf oBuf;
//Sprojection Object declaration
        private FldSpec[] Sprojection;
//dominating Tuple
        private Tuple oneTupleToRuleThemAll;
//Iterator for tuples
        private Iterator[] iter;
//Heapfile for disk storage
        private Heapfile heap;
//BlockNestedLoopSky instance
        private BlockNestedLoopSky bNLS;
//Final Skyline
        private Vector<Tuple> skyline;
//HeapFile Scan Object for BNLS
        private FileScan fScan;
//CondExpr for FileScan
        private CondExpr[] cExpr;
        private String file_name;
        private int _n_out_flds;
        private FldSpec[] _proj_list;
        private CondExpr[] _outFilter;
        private Iterator scan;
        private int MINIBASE_PAGESIZE = 1024;
        private PageId[] bufferPIDs;
        private int _n_Pages;
        private AttrType[] _in_1;
        private short _len_in1;
        private short[] _t1_str_sizes;


//Pass in Sorted Index Files Descending Order
        public BTreeSky(AttrType[] in1,
                        short len_in1,
                        short[] t1_str_sizes,
                        Iterator am1,
                        java.lang.String relationName,
                        int[] pref_list,
                        int pref_list_length,
                        String[] index_file_list,
                        int n_pages)
                        throws IndexException,
			InvalidTypeException,
                        InvalidTupleSizeException,
                        UnknownIndexTypeException,
                        IOException,
                        SortException,
                        IteratorBMException, JoinsException, IndexException, InvalidTupleSizeException,
                			 PageNotReadException, TupleUtilsException, PredEvalException, SortException,
                			LowMemException, UnknowAttrType, UnknownKeyTypeException, Exception
        {
//Set Class Variables
            this._len_in1 = len_in1;
            this._in_1 = in1;
            this._t1_str_sizes = t1_str_sizes;
            this._n_Pages = n_pages;
//Heap File Name
            file_name = "skyline_candidates";
//Initialize Heap file to one named 'skyline_candidates'
            heap = new Heapfile(file_name);
//Initialize Buffer
            buffer = new byte[n_pages][];
            bufferPIDs = new PageId[n_pages];
            get_buffer_pages(n_pages, bufferPIDs,buffer);
//Create new OBuf object for a computation buffer
            oBuf = new OBuf();
            Tuple w = new Tuple();
            w.setHdr(this._len_in1, this._in_1, this._t1_str_sizes);
//Initialize OBuf object to include heapfile storage on flushed
            oBuf.init(buffer, n_pages, w.size(), heap, false);
//Initialize FldSpec object for use in iteration
            Sprojection = new FldSpec[len_in1];
//Initialize CondExpr array;
            cExpr = new CondExpr[pref_list_length];
//Initialize Iterators
            iter = new Iterator[pref_list_length];
//Iterate over IndexFiles and create separate iterators and fldspecs
            for(int j = 0; j<len_in1;j++){
		    Sprojection[j] = new FldSpec(new RelSpec(RelSpec.outer), j+1);
	    }
	    for(int i=0; i<pref_list_length;i++){
                    cExpr[i] = new CondExpr();
                    iter[i] = new IndexScan(new IndexType(IndexType.B_Index),
                        relationName,
                        index_file_list[i],
                        in1,
                        t1_str_sizes,
                        len_in1,
                        len_in1,
                        Sprojection,
                        null,
                        0,
                        false);
            }
            this._in_1 = in1;

            _len_in1 = len_in1;
            _n_out_flds = pref_list_length;
            _proj_list = Sprojection;
            _outFilter = cExpr;
//Get Iterator for Heapfile
            fScan = new FileScan(file_name, this._in_1, _t1_str_sizes, _len_in1,
            _n_out_flds, _proj_list, _outFilter);
//Initialize BNLS object to use heapfile
            runSky();
            bNLS = new BlockNestedLoopSky(in1, len_in1, t1_str_sizes, fScan,
            "skyline_candidates", pref_list, pref_list_length,
            n_pages);

        }

        /**
        *implement the skyline operations using BlockNestedLoopSky
        *and perform tuple comparision to find skyline candidates to pass
        *into the bNLS object.
        *
        */
        public void runSky() throws IOException, JoinsException, IndexException, InvalidTupleSizeException,
			InvalidTypeException, PageNotReadException, TupleUtilsException, PredEvalException, SortException,
			LowMemException, UnknowAttrType, UnknownKeyTypeException, Exception{
//Create Tuple Objects for comparisons and manipulation
                Tuple temp;
                Tuple temp2;
                Tuple dup;
//Set Loop and exit condition
                boolean common = false;
                boolean foundDominantTuple = false;
                boolean duplicate = false;
//scan = iter[0]; //setting up get_next
//Loop over the tuples of the first index_file ( Tuple Field )
                while((temp=iter[0].get_next()) != null && !foundDominantTuple){

                    temp.setHdr(this._len_in1, this._in_1, this._t1_str_sizes);

//Reset Loop Conition
                    common = false;
//Iterate over all iterators for each index file
                    for(int i = 1; i < iter.length; i++){
//Reset Loop condition
                        common = false;
//So Long as no common value has been Found
//and there exists some next tuple in the list
                        while(((temp2=iter[i].get_next())!= null) && !common){
                                temp2.setHdr(this._len_in1, this._in_1, this._t1_str_sizes);
//If we have found a common tuple, end this loop iteration
                            if (temp == temp2){
                                common = true;
                                break;
                            }
//If tuples are not equal, and we have not
//already encountered this tuple put in the oBuf
//and store the tuple in a list of encountered tuples
//for duplicate elimination
                            else if(temp != temp2){
                                common = false;
                                while((dup = oBuf.Get()) != null){
                                    dup.setHdr(this._len_in1, this._in_1, this._t1_str_sizes);
                                    if(dup == temp2){
                                        duplicate = true;
                                        break;
                                    }
                                }
//Scan Heap File for Duplicates
                                if(oBuf.get_buf_status()){
                                    Scan scd = heap.openScan();
                                    Tuple heap_dup;

                                    RID heap_dup_rid = new RID();
//iterate over heap file.
                                    while((heap_dup = scd.getNext(heap_dup_rid))!= null){
                                        heap_dup.setHdr(this._len_in1, this._in_1, this._t1_str_sizes);
                                        if(heap_dup == temp2){
                                            duplicate = true;
                                            break;
                                        }
                                    }
                                    scd.closescan();
                                }
                            }
                            if(!duplicate){
                                this.oBuf.Put(temp2);
                            }
                        }
                        iter[i].close();
                    }
//If the Dominant Tuple has been found, store this tuple
//and set loop exit condition
                    if(common){
                        oneTupleToRuleThemAll = temp;
                        foundDominantTuple = true;
                    }
                }
                iter[0].close();
//flush the buffer and store to heapfile
            oBuf.flush();
            free_buffer_pages(this._n_Pages, bufferPIDs);

        }
        @Override
        public Tuple get_next() throws Exception{
                return bNLS.get_next();


        }
        @Override
        public void close() throws IOException, JoinsException, SortException, IndexException {
	// TODO Auto-generated method stub
                try{
                        heap.deleteFile();
                }
                catch (Exception e){
                        return;
                }



        }


}
