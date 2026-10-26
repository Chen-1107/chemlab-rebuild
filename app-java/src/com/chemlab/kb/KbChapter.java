package com.chemlab.kb;

/** 本地参考书章节：所属书目、标题、分类与起始页。 */
public final class KbChapter {

    public String id = "";
    public String bookId = "";
    public String title = "";
    public String category = "其他";
    public int page;

    @Override
    public String toString() {
        return title;
    }
}
