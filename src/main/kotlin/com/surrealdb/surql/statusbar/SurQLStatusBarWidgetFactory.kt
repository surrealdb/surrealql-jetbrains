package com.surrealdb.surql.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** Registers [SurQLStatusBarWidget] with the IDE status bar for every project. */
class SurQLStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = SurQLStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "SurrealQL Connection"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = SurQLStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
