package top.niunaijun.blackboxa.view.fake

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.ferfalk.simplesearchview.SimpleSearchView
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.FakeLocationBean
import top.niunaijun.blackboxa.databinding.ActivityListBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.BaseActivity

class FakeManagerActivity : BaseActivity() {

    private val viewBinding: ActivityListBinding by inflate()

    private lateinit var mAdapter: RVAdapter<FakeLocationBean>

    private lateinit var viewModel: FakeLocationViewModel

    private var appList: List<FakeLocationBean> = ArrayList()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.fake_location, true)

        mAdapter = RVAdapter<FakeLocationBean>(this,FakeLocationAdapter()).bind(viewBinding.recyclerView)
            .setItemClickListener { _, data, _ ->
                val intent = Intent(this, FollowMyLocationOverlay::class.java)
                intent.putExtra("location", data.fakeLocation)
                intent.putExtra("pkg", data.packageName)
                locationResult.launch(intent)
            }.setItemLongClickListener { _, item, position ->
                disableFakeLocation(item, position)
            }

        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)

        initSearchView()
        initViewModel()
    }

    private fun initSearchView() {
        viewBinding.searchView.setOnQueryTextListener(object : SimpleSearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                filterApp(newText)
                return true
            }
            override fun onQueryTextCleared(): Boolean = true
            override fun onQueryTextSubmit(query: String): Boolean = true
        })
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getFakeLocationFactory()).get(
            FakeLocationViewModel::class.java
        )
        loadAppList()

        viewModel.appsLiveData.observe(this) {
            if (it != null) {
                this.appList = it
                viewBinding.searchView.setQuery("", false)
                filterApp("")
                if (it.isNotEmpty()) {
                    viewBinding.stateView.showContent()
                } else {
                    viewBinding.stateView.showEmpty()
                }
            }
        }
    }

    private fun loadAppList() {
        val userID = intent.getIntExtra("userID", 0)
        viewModel.getInstallAppList(userID)
    }

    private fun filterApp(newText: String) {
        val newList = this.appList.filter {
            it.name.contains(newText, true) or it.packageName.contains(newText, true)
        }
        mAdapter.setItems(newList)
    }

    private fun disableFakeLocation(item: FakeLocationBean, position: Int) {
        val userID = intent.getIntExtra("userID", 0)
        viewModel.setPattern(userID, item.packageName, BLocationManager.CLOSE_MODE)
        item.fakeLocation = null
        mAdapter.replaceAt(position, item)
        toast(getString(R.string.close_fake_location_success, item.name))
    }

    private val locationResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val latitude = data.getDoubleExtra("latitude", 0.0)
                val longitude = data.getDoubleExtra("longitude", 0.0)
                val pkg = data.getStringExtra("pkg") ?: ""
                val userID = intent.getIntExtra("userID", 0)

                viewModel.setPattern(userID, pkg, BLocationManager.OWN_MODE)
                viewModel.setLocation(userID, pkg, BLocation(latitude, longitude))
                toast(getString(R.string.set_location, latitude.toString(), longitude.toString()))
                loadAppList()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)
        val item = menu!!.findItem(R.id.list_search)
        viewBinding.searchView.setMenuItem(item)
        return true
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FakeManagerActivity::class.java)
            context.startActivity(intent)
        }
    }
}
