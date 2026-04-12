/*
 * Copyright (c) 2021 NoLimits Enterprises brock@radenso.com
 *
 * Copyright (c) 2023 jsdx761
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.jsd.x761.ds1pace;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

import com.jsd.x761.ds1pace.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A base activity for menus.
 */
public class MenuActivity extends AppCompatActivity {
  private static final String TAG = "MENU_ACTIVITY";

  protected ArrayList<String> mArrayMenu = new ArrayList<>();
  protected ArrayList<ActivityConfiguration> mIntentMenu = new ArrayList<>();
  protected ArrayList<Integer> mHeaderList = new ArrayList<>();
  private ListView mMenuView;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setContentView(R.layout.base_menu_activity);
    MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
    setSupportActionBar(toolbar);
    toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    mMenuView = findViewById(R.id.menuView);

    addMenuItems();

    MenuAdapter adapter = new MenuAdapter(this, R.layout.base_menu_item, mArrayMenu);

    for(int i = 0; i < mHeaderList.size(); i++) {
      adapter.addHeader(mHeaderList.get(i));
    }

    mMenuView.setAdapter(adapter);
    mMenuView.setOnItemClickListener((parent, view, position, id) -> {
      ActivityConfiguration conf = mIntentMenu.get(position);
      Class<?> nextActivity = conf.cls;

      if(nextActivity == null) {
        return;
      }

      Intent intent = new Intent(MenuActivity.this, nextActivity);
      startActivity(intent);
    });
  }

  protected void addMenuItems() {
    Log.i(TAG, "addMenuItems");
  }

  protected static class ActivityConfiguration {
    public Class<?> cls;

    public ActivityConfiguration(Class<?> c) {
      cls = c;
    }
  }

  protected static class MenuAdapter extends ArrayAdapter<String> {
    private final Set<Integer> headerSet = new TreeSet<>();

    public MenuAdapter(
      Context context, int resource, List<String> objects) {
      super(context, resource, objects);
    }

    public void addHeader(int n) {
      headerSet.add(n);
    }

    @Override
    public View getView(int p, View v, ViewGroup g) {
      TextView item = (TextView)super.getView(p, v, g);

      if(!(headerSet.contains(p))) {
        item.setTypeface(item.getTypeface(), Typeface.NORMAL);
        item.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        return item;

      }

      item.setTypeface(item.getTypeface(), Typeface.BOLD);
      item.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
      return item;
    }
  }
}

