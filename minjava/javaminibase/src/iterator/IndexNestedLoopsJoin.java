package iterator;


import btree.*;
import hash.ClusteredHashIndexScan;
import hash.UnclusteredHashIndexScan;
import heap.*;
import global.*;
import bufmgr.*;
import diskmgr.*;
import index.*;
import java.lang.*;
import java.io.*;
/**
 *
 *  This file contains an implementation of the nested loops join
 *  algorithm as described in the Shapiro paper.
 *  The algorithm is extremely simple:
 *
 *      foreach tuple r in R do
 *          foreach tuple s in S do
 *              if (ri == sj) then add (r, s) to the result.
 */

public class IndexNestedLoopsJoin  extends Iterator
{
    private AttrType      _in1[],  _in2[], Jtypes[];
    private   int        in1_len, in2_len, Index_type, hash, split_pointer, inner_field_num, outer_field_num;
    private   Iterator  outer, inner;
    private   short[] t2_str_sizescopy, t1_str_sizes;
    private   CondExpr OutputFilter[];
    private   CondExpr RightFilter[];
    private FldSpec[] Rprojection;
    private   int        n_buf_pgs;        // # of buffer pages available.
    private   boolean        done,         // Is the join complete
            get_from_outer, is_clustered;                 // if TRUE, a tuple is got from outer
    private   Tuple     outer_tuple, inner_tuple;
    private   Tuple     Jtuple;           // Joined tuple
    private   FldSpec   perm_mat[];
    private   int        nOutFlds;
    private IndexScan inner_scan;
    private String Inner_relation_name, Index_name;


    /**constructor
     *Initialize the two relations which are joined, including relation type,
     *@param in1  Array containing field types of R.
     *@param len_in1  # of columns in R.
     *@param t1_str_sizes shows the length of the string fields.
     *@param in2  Array containing field types of S
     *@param len_in2  # of columns in S
     *@param  t2_str_sizes shows the length of the string fields.
     *@param amt_of_mem  N PAGES
     *@param am1  access method for left i/p to join
     *@param relationName  access heapfile for right i/p to join
     *@param ind_type  index type on right i/p to join
     *@param index_name  index_name of right i/p to join
     *@param is_clust  True if index is clustered, False otherwise
     *@param hash1  Hash key in case of Hash Index
     *@param split_pntr  Split pointer to indicate hash change
     *@param inner_join_attr  inner relation Field_num to join
     *@param outer_join_attr  outer relation Field_num to join
     *@param outFilter   select expressions for Join Condition
     *@param rightFilter reference to filter applied on right i/p
     *@param proj_list shows what input fields go where in the output tuple
     *@param n_out_flds number of outer relation fields
     *@exception IOException some I/O fault
     *@exception NestedLoopException exception from this class
     */

    /*Currently assumed that Iterator is passed for outer relation and
    name of inner relation is passed along with the index_file name
     */
    public IndexNestedLoopsJoin( AttrType    in1[],
                             int     len_in1,
                             short   t1_str_sizes[],
                             AttrType    in2[],
                             int     len_in2,
                             short   t2_str_sizes[],
                             int     amt_of_mem,
                             Iterator     am1,
                             String relationName,
                             int ind_type,
                             boolean is_clust,
                             int hash1,
                             int split_pntr,
                             int inner_join_attr,
                             int outer_join_attr,
                             String index_name,
                             CondExpr outFilter[],
                             CondExpr rightFilter[],
                             FldSpec   proj_list[],
                             int        n_out_flds
    ) throws IOException, NestedLoopException, UnknownIndexTypeException, InvalidTypeException, IndexException, InvalidTupleSizeException, KeyNotMatchException, IteratorException, PinPageException, ConstructPageException, UnknownKeyTypeException, UnpinPageException, InvalidSelectionException, Exception {

        if (amt_of_mem < 7) {
            // Dominant case of Unclustered Hash Index with
            // minimum 7 page overall requirement is considered as limiting factor
            throw new Exception("Not enough Buffer pages. " +
                    "\n Minimum required: 7 \t Available: " + amt_of_mem);
        }

        _in1 = new AttrType[in1.length];
        _in2 = new AttrType[in2.length];
        System.arraycopy(in1,0,_in1,0,in1.length);
        System.arraycopy(in2,0,_in2,0,in2.length);
        in1_len = len_in1;
        in2_len = len_in2;
        Inner_relation_name = relationName;
        Index_type = ind_type;
        Index_name = index_name;
        is_clustered = is_clust;
        hash = hash1;
        split_pointer = split_pntr;
        inner_field_num = inner_join_attr;
        this.t1_str_sizes = t1_str_sizes.clone();
        
        outer_field_num = outer_join_attr;

        outer = am1;
        t2_str_sizescopy =  t2_str_sizes;
        inner_tuple = new Tuple();
        Jtuple = new Tuple();
        OutputFilter = outFilter;
        RightFilter  = rightFilter;

        n_buf_pgs    = amt_of_mem;
        inner = null;
        done  = false;
        get_from_outer = true;

        Jtypes = new AttrType[n_out_flds];
        short[]    t_size;

        perm_mat = proj_list;
        nOutFlds = n_out_flds;
        try {
            t_size = TupleUtils.setup_op_tuple(Jtuple, Jtypes,
                    in1, len_in1, in2, len_in2,
                    t1_str_sizes, t2_str_sizes,
                    proj_list, nOutFlds);
        }catch (TupleUtilsException e){
            throw new NestedLoopException(e,"TupleUtilsException is caught by IndexNestedLoopsJoin.java");
        }
    }

    /**
     *@return The joined tuple is returned
     *@exception IOException I/O errors
     *@exception JoinsException some join exception
     *@exception IndexException exception from super class
     *@exception InvalidTupleSizeException invalid tuple size
     *@exception InvalidTypeException tuple type not valid
     *@exception PageNotReadException exception from lower layer
     *@exception TupleUtilsException exception from using tuple utilities
     *@exception PredEvalException exception from PredEval class
     *@exception SortException sort exception
     *@exception LowMemException memory error
     *@exception UnknowAttrType attribute type unknown
     *@exception UnknownKeyTypeException key type unknown
     *@exception Exception other exceptions
     */
    public Tuple get_next()
            throws IOException,
            JoinsException ,
            IndexException,
            InvalidTupleSizeException,
            InvalidTypeException,
            PageNotReadException,
            TupleUtilsException,
            PredEvalException,
            SortException,
            LowMemException,
            UnknowAttrType,
            UnknownKeyTypeException,
            Exception
    {

        if (done)
            return null;

        do
        {
            // If get_from_outer is true, Get a tuple from the outer, delete
            // an existing scan on the file, and reopen a new scan on the file.
            // If a get_next on the outer returns DONE?, then the nested loops
            //join is done too.

            if (get_from_outer == true)
            {
                get_from_outer = false;
                if (inner != null)     // If this not the first time,
                {
                    // close scan
                    inner = null;
                }

                if ((outer_tuple=outer.get_next()) == null)
                {
                    done = true;
                    if (inner != null)
                    {

                        inner = null;
                    }

                    return null;
                }
            }  // ENDS: if (get_from_outer == TRUE)


            // Get the value of outer tuple field in the select condition
            // to be passed for Index Scan of inner relation
            // Prepare Select condition for Index_Scan with the retrieved value
            outer_tuple.setHdr((short)_in1.length, _in1, t1_str_sizes);
            CondExpr [] scan_selects = get_index_scan_selects(OutputFilter, outer_tuple);

            Rprojection = new FldSpec[in2_len];

            for (int i = 0; i < in2_len; i++) {
                // projection for inner relation fields
                Rprojection[i] = new FldSpec(new RelSpec(RelSpec.outer), i + 1);
            }

            if (inner == null) {
                switch (Index_type) {
                    case (IndexType.B_Index):
                        if (is_clustered) {
                            // Clustered BTree Index
                            inner = new ClusteredBtreeIndexScan(Index_name, this._in2, t2_str_sizescopy, scan_selects, inner_field_num, false);
                        } else {
                            // Un-Clustered BTree Index
                            inner = new IndexScan(new IndexType(IndexType.B_Index), Inner_relation_name, Index_name, this._in2, t2_str_sizescopy, in2_len,
                                    in2_len, Rprojection, scan_selects, inner_field_num, false);
                        }
                        break;
                    case (IndexType.Hash):
                        if (is_clustered) {
                            // Clustered Hash Index
                            inner = new ClusteredHashIndexScan(Index_name, Inner_relation_name, this._in2, t2_str_sizescopy, inner_field_num, outer_field_num, outer_tuple, hash, split_pointer);
                        } else {
                            // Un-Clustered Hash Index
                            inner = new UnclusteredHashIndexScan(Index_name, this._in2, t2_str_sizescopy, inner_field_num, Inner_relation_name, outer_field_num, outer_tuple, hash, split_pointer);
                        }
                        break;
                    default:
                        break;
                }
            }

            try {
                RID rid = new RID();
                // For every outer rel tuple, get the list of all inner rel
                // tuples through get_next() of appropriate index scan to be joined together
                while ((inner_tuple = inner.get_next()) != null) {
                    inner_tuple.setHdr((short) in2_len, _in2, t2_str_sizescopy);
                    if (PredEval.Eval(RightFilter, inner_tuple, null, _in2, null) == true) {
                        if (PredEval.Eval(OutputFilter, outer_tuple, inner_tuple, _in1, _in2) == true) {
                            // Apply a projection on the outer and inner tuples.
                            Projection.Join(outer_tuple, _in1,
                                    inner_tuple, _in2,
                                    Jtuple, perm_mat, nOutFlds);
                            return Jtuple;
                        }
                    }
                }
            }
            catch (Exception e){
                System.err.println("Exception caught while scanning inner relation and joining");
                e.printStackTrace();
                if (inner!=null)
                    inner.close();
                return null;
            }

            inner.close();

            // There has been no match. (otherwise, we would have 
            //returned from the while loop. Hence, inner is
            //exhausted, => set get_from_outer = TRUE, go to top of loop

            get_from_outer = true; // Loop back to top and get next outer tuple.
        } while (true);
    }

    /***
    Used to set the operand of passed select (CondExpr) Expression based on the other object
     @param out_op Select exp to be copied from
     @param inp_op Select exp to copy to
     @param type type of the operand
     ***/
    public void set_select_operand(Operand out_op, Operand inp_op, AttrType type){
        switch (type.attrType){
            case (AttrType.attrInteger):
                out_op.integer = inp_op.integer;
                break;
            case (AttrType.attrReal):
                out_op.real = inp_op.real;
                break;
            case (AttrType.attrString):
                out_op.string = inp_op.string;
                break;
            case (AttrType.attrSymbol):
                if (inp_op.symbol.relation.key == RelSpec.outer) {
                    out_op.symbol = new FldSpec( new RelSpec(RelSpec.outer), inp_op.symbol.offset);
                }
                else if (inp_op.symbol.relation.key == RelSpec.innerRel) {
                    out_op.symbol = new FldSpec( new RelSpec(RelSpec.innerRel), inp_op.symbol.offset);
                }
                break;
            default:
                System.err.println("INLJ - set_select_operand(): AttrType not supported");
                break;
        }

    }

    /***
     Used to set the outer rel tuple join attribute value on select (CondExpr) Expression for IndexScan call
     @param outputFilter Select exp to be referred
     @param outer_tup Tuple to get the value from
     ***/
    public CondExpr[] get_index_scan_selects (CondExpr [] outputFilter, Tuple outer_tup) throws Exception
    {
        CondExpr [] ind_Scan_select = null;

        if (outputFilter != null) {
            ind_Scan_select = new CondExpr[outputFilter.length];    // To be used for index scan
            int i = 0;
            CondExpr temp_ptr;
            while(outputFilter[i] != null) {
                temp_ptr = outputFilter[i];
                while (temp_ptr != null) {
                    ind_Scan_select[i] = new CondExpr();
                    ind_Scan_select[i].type1 = new AttrType(temp_ptr.type1.attrType);
                    set_select_operand(ind_Scan_select[i].operand1, temp_ptr.operand1, temp_ptr.type1);
                    ind_Scan_select[i].type2 = new AttrType(temp_ptr.type2.attrType);
                    set_select_operand(ind_Scan_select[i].operand2, temp_ptr.operand2, temp_ptr.type2);
                    switch (temp_ptr.op.attrOperator) {
                        case (AttrOperator.aopGT) :
                            ind_Scan_select[i].op = new AttrOperator(AttrOperator.aopLT);
                            break;
                        case (AttrOperator.aopGE) :
                            ind_Scan_select[i].op = new AttrOperator(AttrOperator.aopLE);
                            break;
                        case (AttrOperator.aopLT) :
                            ind_Scan_select[i].op = new AttrOperator(AttrOperator.aopGT);
                            break;
                        case (AttrOperator.aopLE) :
                            ind_Scan_select[i].op = new AttrOperator(AttrOperator.aopGE);
                            break;
                        default :
                            ind_Scan_select[i].op = new AttrOperator(temp_ptr.op.attrOperator);
                            break;
                    }
                    ind_Scan_select[i].next = temp_ptr.next;

                    temp_ptr = temp_ptr.next;
                }
                i++;
            }
            ind_Scan_select[i] = null;
            i = 0;
            temp_ptr = null;

            while (outputFilter[i] != null) {
                temp_ptr = outputFilter[i];

                while (temp_ptr != null) {
                    int fld_num = temp_ptr.operand1.symbol.offset;
                    if (temp_ptr.operand1.symbol.relation.key == RelSpec.outer) {
                        switch (_in1[fld_num-1].attrType) {
                            case AttrType.attrInteger:                // Get and set integer value.
                                try {
                                    int t_i = outer_tup.getIntFld(fld_num);
                                    ind_Scan_select[i].type1 = new AttrType(AttrType.attrInteger);
                                    ind_Scan_select[i].operand1.integer = t_i;
                                } catch (FieldNumberOutOfBoundException e) {
                                    throw new TupleUtilsException(e, "FieldNumberOutOfBoundException is caught by IndexNestedLoopsJoin.java");
                                }
                                break;
                            case AttrType.attrReal:                // Get and set float value.
                                try {
                                    float t_r = outer_tuple.getFloFld(fld_num);
                                    ind_Scan_select[i].type1 = new AttrType(AttrType.attrReal);
                                    ind_Scan_select[i].operand1.real = t_r;
                                } catch (FieldNumberOutOfBoundException e) {
                                    throw new TupleUtilsException(e, "FieldNumberOutOfBoundException is caught by IndexNestedLoopsJoin.java");
                                }
                                break;
                            case AttrType.attrString:                // Get and set String value.
                                try {
                                    String t_s = outer_tuple.getStrFld(fld_num);
                                    ind_Scan_select[i].type1 = new AttrType(AttrType.attrString);
                                    ind_Scan_select[i].operand1.string = t_s;
                                } catch (FieldNumberOutOfBoundException e) {
                                    throw new TupleUtilsException(e, "FieldNumberOutOfBoundException is caught by IndexNestedLoopsJoin.java");
                                }
                                break;
                            default:
                                throw new UnknowAttrType(null, "Don't know how to handle attrSymbol, attrNull");
                        }
                    }
                    temp_ptr = temp_ptr.next;
                }
                i++;
            }
        }
        return ind_Scan_select;
    }

    /**
     * implement the abstract method close() from super class Iterator
     *to finish cleaning up
     *@exception IOException I/O error from lower layers
     *@exception JoinsException join error from lower layers
     *@exception IndexException index access error 
     */
    public void close() throws JoinsException, IOException,IndexException
    {
        if (!closeFlag) {

            try {
                outer.close();
            }catch (Exception e) {
                throw new JoinsException(e, "NestedLoopsJoin.java: error in closing iterator.");
            }
            closeFlag = true;
        }
    }
}
