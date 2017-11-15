//
//   Copyright 2017  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.ext.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.util.Bytes;

import io.warp10.continuum.MetadataUtils;
import io.warp10.continuum.egress.HBaseStoreClient;
import io.warp10.continuum.gts.GTSHelper;
import io.warp10.continuum.gts.GeoTimeSerie;
import io.warp10.continuum.store.StoreClient;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

public class HLOCATE extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  public HLOCATE(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object top = stack.pop();
    
    if (!(top instanceof List)) {
      throw new WarpScriptException(getName() + " expects a list of Geo Time Series on top of the stack.");
    }
    
    StoreClient sc = stack.getStoreClient();
    
    if (!(sc instanceof HBaseStoreClient)) {
      throw new WarpScriptException(getName() + " only works with an HBase storage backend.");
    }
    
    HBaseStoreClient hbsc = (HBaseStoreClient) sc;
    
    RegionLocator locator = null;
    List<HRegionLocation>  regions = null;
    
    try {
      locator = hbsc.getRegionLocator();
      regions = locator.getAllRegionLocations();
    } catch (IOException ioe) {
      throw new WarpScriptException(ioe);
    }

    //
    // Sort regions by start key
    //
    
    regions.sort(new Comparator<HRegionLocation>() {
      @Override
      public int compare(HRegionLocation o1, HRegionLocation o2) {
        return Bytes.compareTo(o1.getRegionInfo().getStartKey(), o2.getRegionInfo().getStartKey());
      }
      
    });
    
    //
    // Sort GTS by class/labels Id
    // We assume class/labels Ids are present in the GTS
    //
    
    List<GeoTimeSerie> lgts = (List<GeoTimeSerie>) top;
    
    lgts.sort(new Comparator<GeoTimeSerie>() {
      @Override
      public int compare(GeoTimeSerie o1, GeoTimeSerie o2) {
        return MetadataUtils.compare(o1.getMetadata(), o2.getMetadata());
      }
    });
    
    //
    // Now loop over the GeoTimeSeries list, checking in which region they fall
    //
    
    int gtsidx = 0;
    int regionidx = 0;
    
    List<List<String>> locations = new ArrayList<List<String>>();
    
    byte[] startrow = regions.get(regionidx).getRegionInfo().getStartKey();
    byte[] endrow = regions.get(regionidx).getRegionInfo().getEndKey();
    byte[] rowprefix = MetadataUtils.HBaseRowKeyPrefix(lgts.get(gtsidx).getMetadata());
    String selector = GTSHelper.buildSelector(lgts.get(gtsidx));
    
    while (gtsidx < lgts.size()) {
      
      // if rowprefix is before the current region it means there is a problem, so increase gtsidx as it is not
      // in a region!
      
      if (Bytes.compareTo(rowprefix, 0, rowprefix.length, startrow, 0, rowprefix.length) < 0) {
        gtsidx++;
        rowprefix = MetadataUtils.HBaseRowKeyPrefix(lgts.get(gtsidx).getMetadata());
        selector = GTSHelper.buildSelector(lgts.get(gtsidx));
        continue;
      }
      
      // rowprefix is after the current region, so increase region idx
      if (Bytes.compareTo(rowprefix, 0, rowprefix.length, endrow, 0, rowprefix.length) > 0) {
        if (regionidx == regions.size() - 1) {
          continue;
        }
        regionidx++;
        startrow = regions.get(regionidx).getRegionInfo().getStartKey();
        endrow = regions.get(regionidx).getRegionInfo().getEndKey();
        continue;
      }
      
      //
      // The current rowprefix is in the current region, add a location info
      //
      
      List<String> location = new ArrayList<String>();
      
      location.add(selector);
      location.add(regions.get(regionidx).getHostnamePort().toString());
      location.add(regions.get(regionidx).getRegionInfo().getEncodedName());
      
      locations.add(location);
      
      gtsidx++;
    }
    
    stack.push(locations);
    
    return stack;
  }
}