package org.koitharu.kotatsu.settings.sources.accounts

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.container
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentSourceAccountsBinding
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.SettingsActivity

@AndroidEntryPoint
class SourceAccountsFragment :
	BaseFragment<FragmentSourceAccountsBinding>(),
	OnListItemClickListener<SourceAccountItem>,
	RecyclerViewOwner {

	private val viewModel by viewModels<SourceAccountsViewModel>()
	private var accountsAdapter: SourceAccountsAdapter? = null
	private var pendingAuthSource: MangaSource? = null

	private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val source = pendingAuthSource
		pendingAuthSource = null
		if (result.resultCode == Activity.RESULT_OK && source != null) {
			viewModel.refreshSource(source)
		}
	}

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentSourceAccountsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentSourceAccountsBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		accountsAdapter = SourceAccountsAdapter(this)
		binding.recyclerView.setHasFixedSize(true)
		binding.recyclerView.adapter = accountsAdapter

		viewModel.items.observe(viewLifecycleOwner) { list ->
			accountsAdapter?.emit(list)
			binding.textViewEmpty.isVisible = list.isEmpty() && !viewModel.isLoading.value
		}
		viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
			binding.progressBar.isVisible = isLoading
			if (!isLoading) {
				binding.textViewEmpty.isVisible = viewModel.items.value.isEmpty()
			}
		}
		viewModel.onError.observeEvent(
			viewLifecycleOwner,
			SnackbarErrorObserver(binding.recyclerView, this, exceptionResolver) {
				viewModel.refreshAll()
			},
		)
		viewModel.onActionDone.observeEvent(
			viewLifecycleOwner,
			ReversibleActionObserver(binding.recyclerView),
		)
		addMenuProvider(AccountsMenuProvider())
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		val isTablet = !resources.getBoolean(R.bool.is_tablet)
		val isMaster = container?.id == R.id.container_master
		v.setPaddingRelative(
			if (isTablet && !isMaster) 0 else barsInsets.start(v),
			0,
			if (isTablet && isMaster) 0 else barsInsets.end(v),
			0,
		)
		viewBinding?.recyclerView?.updatePadding(bottom = barsInsets.bottom)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onResume() {
		super.onResume()
		val title = getString(R.string.source_accounts)
		(activity as? SettingsActivity)?.setSectionTitle(title) ?: activity?.setTitle(title)
		viewModel.refreshAll(showLoading = false)
	}

	override fun onDestroyView() {
		accountsAdapter = null
		super.onDestroyView()
	}

	override fun onItemClick(item: SourceAccountItem, view: View) {
		val ctx = context ?: return
		if (!item.isSignedIn && !item.hasSignUpUrl) {
			launchSourceAuth(item.source, startSignUp = false)
			return
		}
		val actions = buildList {
			if (item.isSignedIn) {
				add(ctx.getString(R.string.logout) to { viewModel.signOut(item.source) })
				add(ctx.getString(R.string.source_account_switch) to { launchSourceAuth(item.source, startSignUp = false) })
			} else {
				add(ctx.getString(R.string.sign_in) to { launchSourceAuth(item.source, startSignUp = false) })
				if (item.hasSignUpUrl) {
					add(ctx.getString(R.string.sign_up) to { launchSourceAuth(item.source, startSignUp = true) })
				}
			}
			add(ctx.getString(R.string.settings) to { router.openSourceSettings(item.source) })
		}
		buildAlertDialog(ctx) {
			setTitle(item.source.getTitle(ctx))
			setItems(actions.map { it.first }.toTypedArray()) { _, which ->
				actions[which].second.invoke()
			}
			setNegativeButton(R.string.close, null)
		}.show()
	}

	override fun onItemLongClick(item: SourceAccountItem, view: View): Boolean {
		router.openSourceSettings(item.source)
		return true
	}

	private fun launchSourceAuth(source: MangaSource, startSignUp: Boolean) {
		val ctx = context ?: return
		pendingAuthSource = source
		authLauncher.launch(AppRouter.sourceAuthIntent(ctx, source, startSignUp))
	}

	private inner class AccountsMenuProvider :
		MenuProvider,
		MenuItem.OnActionExpandListener,
		SearchView.OnQueryTextListener {

		override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
			menuInflater.inflate(R.menu.opt_source_accounts, menu)
			val searchMenuItem = menu.findItem(R.id.action_search)
			searchMenuItem.setOnActionExpandListener(this)
			val searchView = searchMenuItem.actionView as SearchView
			searchView.setOnQueryTextListener(this)
			searchView.setIconifiedByDefault(false)
			searchView.queryHint = searchMenuItem.title
		}

		override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
			R.id.action_refresh -> {
				viewModel.refreshAll(showLoading = true)
				true
			}

			else -> false
		}

		override fun onMenuItemActionExpand(item: MenuItem): Boolean {
			(activity as? AppBarOwner)?.appBar?.setExpanded(false, true)
			return true
		}

		override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
			(item.actionView as SearchView).setQuery("", false)
			return true
		}

		override fun onQueryTextSubmit(query: String?): Boolean = false

		override fun onQueryTextChange(newText: String?): Boolean {
			viewModel.performSearch(newText)
			return true
		}
	}
}
