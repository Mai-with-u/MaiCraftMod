// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 记账口的一层过滤：最外层要的东西的入包不记，其余照传。最外层要的那一件由发起的任务按这次净拿到几件统一结算，
 * 来源动作再记就重了；自制的中间产物没有别人记，原样放过去。
 */
final class ExceptOutermostGain implements TaskRecords {
    private final TaskRecords delegate;
    private final WantedItem wanted;
    private final ReadsItemTags tags;

    ExceptOutermostGain(TaskRecords delegate, WantedItem wanted, ReadsItemTags tags) {
        this.delegate = delegate;
        this.wanted = wanted;
        this.tags = tags;
    }

    @Override public void change(Change change) {
        if (change.kind() == Change.Kind.ITEM_GAINED && wanted.matches(change.what(), tags.tagsOf(change.what()))) {
            return;
        }
        delegate.change(change);
    }

    @Override public void unconfirmed(Change change) {
        delegate.unconfirmed(change);
    }

    @Override public void attempt(String tried, String whatHappened) {
        delegate.attempt(tried, whatHappened);
    }
}
