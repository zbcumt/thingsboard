/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.service;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.asset.AssetSearchQuery;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.AssetSearchQueryFilter;
import org.thingsboard.server.common.data.query.DeviceSearchQueryFilter;
import org.thingsboard.server.common.data.query.DeviceTypeFilter;
import org.thingsboard.server.common.data.query.EntityCountQuery;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityDataSortOrder;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.EntityListFilter;
import org.thingsboard.server.common.data.query.FilterPredicateValue;
import org.thingsboard.server.common.data.query.KeyFilter;
import org.thingsboard.server.common.data.query.NumericFilterPredicate;
import org.thingsboard.server.common.data.query.RelationsQueryFilter;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.EntitySearchDirection;
import org.thingsboard.server.common.data.relation.EntityTypeFilter;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.dao.attributes.AttributesService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public abstract class BaseEntityServiceTest extends AbstractServiceTest {

    @Autowired
    private AttributesService attributesService;

    private TenantId tenantId;

    @Before
    public void before() {
        Tenant tenant = new Tenant();
        tenant.setTitle("My tenant");
        Tenant savedTenant = tenantService.saveTenant(tenant);
        Assert.assertNotNull(savedTenant);
        tenantId = savedTenant.getId();
    }

    @After
    public void after() {
        tenantService.deleteTenant(tenantId);
    }

    @Test
    public void testCountEntitiesByQuery() throws InterruptedException {
        List<Device> devices = new ArrayList<>();
        for (int i = 0; i < 97; i++) {
            Device device = new Device();
            device.setTenantId(tenantId);
            device.setName("Device" + i);
            device.setType("default");
            device.setLabel("testLabel" + (int) (Math.random() * 1000));
            devices.add(deviceService.saveDevice(device));
        }

        DeviceTypeFilter filter = new DeviceTypeFilter();
        filter.setDeviceType("default");
        filter.setDeviceNameFilter("");

        EntityCountQuery countQuery = new EntityCountQuery(filter);

        long count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(97, count);

        filter.setDeviceType("unknown");
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(0, count);

        filter.setDeviceType("default");
        filter.setDeviceNameFilter("Device1");
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(11, count);

        EntityListFilter entityListFilter = new EntityListFilter();
        entityListFilter.setEntityType(EntityType.DEVICE);
        entityListFilter.setEntityList(devices.stream().map(Device::getId).map(DeviceId::toString).collect(Collectors.toList()));

        countQuery = new EntityCountQuery(entityListFilter);
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(97, count);

        deviceService.deleteDevicesByTenantId(tenantId);
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(0, count);
    }

    @Test
    public void testCountHierarchicalEntitiesByQuery() throws InterruptedException {
        List<Asset> assets = new ArrayList<>();
        List<Device> devices = new ArrayList<>();
        createTestHierarchy(assets, devices, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        RelationsQueryFilter filter = new RelationsQueryFilter();
        filter.setRootEntity(tenantId);
        filter.setDirection(EntitySearchDirection.FROM);

        EntityCountQuery countQuery = new EntityCountQuery(filter);

        long count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(30, count);

        filter.setFilters(Collections.singletonList(new EntityTypeFilter("Contains", Collections.singletonList(EntityType.DEVICE))));
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(25, count);

        filter.setRootEntity(devices.get(0).getId());
        filter.setDirection(EntitySearchDirection.TO);
        filter.setFilters(Collections.singletonList(new EntityTypeFilter("Manages", Collections.singletonList(EntityType.TENANT))));
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(1, count);

        DeviceSearchQueryFilter filter2 = new DeviceSearchQueryFilter();
        filter2.setRootEntity(tenantId);
        filter2.setDirection(EntitySearchDirection.FROM);
        filter2.setRelationType("Contains");

        countQuery = new EntityCountQuery(filter2);

        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(25, count);

        filter2.setDeviceTypes(Arrays.asList("default0", "default1"));
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(10, count);

        filter2.setRootEntity(devices.get(0).getId());
        filter2.setDirection(EntitySearchDirection.TO);
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(0, count);

        AssetSearchQueryFilter filter3 = new AssetSearchQueryFilter();
        filter3.setRootEntity(tenantId);
        filter3.setDirection(EntitySearchDirection.FROM);
        filter3.setRelationType("Manages");

        countQuery = new EntityCountQuery(filter3);

        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(5, count);

        filter3.setAssetTypes(Arrays.asList("type0", "type1"));
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(2, count);

        filter3.setRootEntity(devices.get(0).getId());
        filter3.setDirection(EntitySearchDirection.TO);
        count = entityService.countEntitiesByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), countQuery);
        Assert.assertEquals(0, count);
    }

    @Test
    public void testHierarchicalFindEntityDataWithAttributesByQuery() throws ExecutionException, InterruptedException {
        List<Asset> assets = new ArrayList<>();
        List<Device> devices = new ArrayList<>();
        List<Long> temperatures = new ArrayList<>();
        List<Long> highTemperatures = new ArrayList<>();
        createTestHierarchy(assets, devices, new ArrayList<>(), new ArrayList<>(), temperatures, highTemperatures);

        List<ListenableFuture<List<Void>>> attributeFutures = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            attributeFutures.add(saveLongAttribute(device.getId(), "temperature", temperatures.get(i), DataConstants.CLIENT_SCOPE));
        }
        Futures.successfulAsList(attributeFutures).get();

        RelationsQueryFilter filter = new RelationsQueryFilter();
        filter.setRootEntity(tenantId);
        filter.setDirection(EntitySearchDirection.FROM);
        filter.setFilters(Collections.singletonList(new EntityTypeFilter("Contains", Collections.singletonList(EntityType.DEVICE))));

        EntityDataSortOrder sortOrder = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.ENTITY_FIELD, "createdTime"), EntityDataSortOrder.Direction.ASC
        );
        EntityDataPageLink pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        List<EntityKey> entityFields = Collections.singletonList(new EntityKey(EntityKeyType.ENTITY_FIELD, "name"));
        List<EntityKey> latestValues = Collections.singletonList(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));

        EntityDataQuery query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, null);
        PageData<EntityData> data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
        List<EntityData> loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(25, loadedEntities.size());
        List<String> loadedTemperatures = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ATTRIBUTE).get("temperature").getValue()).collect(Collectors.toList());
        List<String> deviceTemperatures = temperatures.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());
        Assert.assertEquals(deviceTemperatures, loadedTemperatures);

        pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        KeyFilter highTemperatureFilter = new KeyFilter();
        highTemperatureFilter.setKey(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));
        NumericFilterPredicate predicate = new NumericFilterPredicate();
        predicate.setValue(FilterPredicateValue.fromDouble(45));
        predicate.setOperation(NumericFilterPredicate.NumericOperation.GREATER);
        highTemperatureFilter.setPredicate(predicate);
        List<KeyFilter> keyFilters = Collections.singletonList(highTemperatureFilter);

        query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, keyFilters);

        data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);

        loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(highTemperatures.size(), loadedEntities.size());

        List<String> loadedHighTemperatures = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ATTRIBUTE).get("temperature").getValue()).collect(Collectors.toList());
        List<String> deviceHighTemperatures = highTemperatures.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());

        Assert.assertEquals(deviceHighTemperatures, loadedHighTemperatures);

        deviceService.deleteDevicesByTenantId(tenantId);
    }

    @Test
    public void testHierarchicalFindDevicesWithAttributesByQuery() throws ExecutionException, InterruptedException {
        List<Asset> assets = new ArrayList<>();
        List<Device> devices = new ArrayList<>();
        List<Long> temperatures = new ArrayList<>();
        List<Long> highTemperatures = new ArrayList<>();
        createTestHierarchy(assets, devices, new ArrayList<>(), new ArrayList<>(), temperatures, highTemperatures);

        List<ListenableFuture<List<Void>>> attributeFutures = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            attributeFutures.add(saveLongAttribute(device.getId(), "temperature", temperatures.get(i), DataConstants.CLIENT_SCOPE));
        }
        Futures.successfulAsList(attributeFutures).get();

        DeviceSearchQueryFilter filter = new DeviceSearchQueryFilter();
        filter.setRootEntity(tenantId);
        filter.setDirection(EntitySearchDirection.FROM);
        filter.setRelationType("Contains");

        EntityDataSortOrder sortOrder = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.ENTITY_FIELD, "createdTime"), EntityDataSortOrder.Direction.ASC
        );
        EntityDataPageLink pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        List<EntityKey> entityFields = Collections.singletonList(new EntityKey(EntityKeyType.ENTITY_FIELD, "name"));
        List<EntityKey> latestValues = Collections.singletonList(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));

        EntityDataQuery query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, null);
        PageData<EntityData> data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
        List<EntityData> loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(25, loadedEntities.size());
        loadedEntities.forEach(entity -> Assert.assertTrue(devices.stream().map(Device::getId).collect(Collectors.toSet()).contains(entity.getEntityId())));
        List<String> loadedTemperatures = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ATTRIBUTE).get("temperature").getValue()).collect(Collectors.toList());
        List<String> deviceTemperatures = temperatures.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());
        Assert.assertEquals(deviceTemperatures, loadedTemperatures);

        pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        KeyFilter highTemperatureFilter = new KeyFilter();
        highTemperatureFilter.setKey(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));
        NumericFilterPredicate predicate = new NumericFilterPredicate();
        predicate.setValue(FilterPredicateValue.fromDouble(45));
        predicate.setOperation(NumericFilterPredicate.NumericOperation.GREATER);
        highTemperatureFilter.setPredicate(predicate);
        List<KeyFilter> keyFilters = Collections.singletonList(highTemperatureFilter);

        query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, keyFilters);

        data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);

        loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(highTemperatures.size(), loadedEntities.size());

        List<String> loadedHighTemperatures = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ATTRIBUTE).get("temperature").getValue()).collect(Collectors.toList());
        List<String> deviceHighTemperatures = highTemperatures.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());

        Assert.assertEquals(deviceHighTemperatures, loadedHighTemperatures);

        deviceService.deleteDevicesByTenantId(tenantId);
    }

    @Test
    public void testHierarchicalFindAssetsWithAttributesByQuery() throws ExecutionException, InterruptedException {
        List<Asset> assets = new ArrayList<>();
        List<Device> devices = new ArrayList<>();
        List<Long> consumptions = new ArrayList<>();
        List<Long> highConsumptions = new ArrayList<>();
        createTestHierarchy(assets, devices, consumptions, highConsumptions, new ArrayList<>(), new ArrayList<>());

        List<ListenableFuture<List<Void>>> attributeFutures = new ArrayList<>();
        for (int i = 0; i < assets.size(); i++) {
            Asset asset = assets.get(i);
            attributeFutures.add(saveLongAttribute(asset.getId(), "consumption", consumptions.get(i), DataConstants.SERVER_SCOPE));
        }
        Futures.successfulAsList(attributeFutures).get();

        AssetSearchQueryFilter filter = new AssetSearchQueryFilter();
        filter.setRootEntity(tenantId);
        filter.setDirection(EntitySearchDirection.FROM);
        filter.setRelationType("Manages");

        EntityDataSortOrder sortOrder = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.ENTITY_FIELD, "createdTime"), EntityDataSortOrder.Direction.ASC
        );
        EntityDataPageLink pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        List<EntityKey> entityFields = Collections.singletonList(new EntityKey(EntityKeyType.ENTITY_FIELD, "name"));
        List<EntityKey> latestValues = Collections.singletonList(new EntityKey(EntityKeyType.ATTRIBUTE, "consumption"));

        EntityDataQuery query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, null);
        PageData<EntityData> data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
        List<EntityData> loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(5, loadedEntities.size());
        List<String> loadedTemperatures = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ATTRIBUTE).get("consumption").getValue()).collect(Collectors.toList());
        List<String> deviceTemperatures = consumptions.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());
        Assert.assertEquals(deviceTemperatures, loadedTemperatures);

        pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        KeyFilter highTemperatureFilter = new KeyFilter();
        highTemperatureFilter.setKey(new EntityKey(EntityKeyType.ATTRIBUTE, "consumption"));
        NumericFilterPredicate predicate = new NumericFilterPredicate();
        predicate.setValue(FilterPredicateValue.fromDouble(50));
        predicate.setOperation(NumericFilterPredicate.NumericOperation.GREATER);
        highTemperatureFilter.setPredicate(predicate);
        List<KeyFilter> keyFilters = Collections.singletonList(highTemperatureFilter);

        query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, keyFilters);

        data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);

        loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(highConsumptions.size(), loadedEntities.size());

        List<String> loadedHighTemperatures = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ATTRIBUTE).get("consumption").getValue()).collect(Collectors.toList());
        List<String> deviceHighTemperatures = highConsumptions.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());

        Assert.assertEquals(deviceHighTemperatures, loadedHighTemperatures);

        deviceService.deleteDevicesByTenantId(tenantId);
    }

    private void createTestHierarchy(List<Asset> assets, List<Device> devices, List<Long> consumptions, List<Long> highConsumptions, List<Long> temperatures, List<Long> highTemperatures) throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            Asset asset = new Asset();
            asset.setTenantId(tenantId);
            asset.setName("Asset" + i);
            asset.setType("type" + i);
            asset.setLabel("AssetLabel" + i);
            asset = assetService.saveAsset(asset);
            //TO make sure devices have different created time
            Thread.sleep(1);
            assets.add(asset);
            EntityRelation er = new EntityRelation();
            er.setFrom(tenantId);
            er.setTo(asset.getId());
            er.setType("Manages");
            er.setTypeGroup(RelationTypeGroup.COMMON);
            relationService.saveRelation(tenantId, er);
            long consumption = (long) (Math.random() * 100);
            consumptions.add(consumption);
            if (consumption > 50) {
                highConsumptions.add(consumption);
            }
            for (int j = 0; j < 5; j++) {
                Device device = new Device();
                device.setTenantId(tenantId);
                device.setName("A" + i + "Device" + j);
                device.setType("default" + j);
                device.setLabel("testLabel" + (int) (Math.random() * 1000));
                device = deviceService.saveDevice(device);
                //TO make sure devices have different created time
                Thread.sleep(1);
                devices.add(device);
                er = new EntityRelation();
                er.setFrom(asset.getId());
                er.setTo(device.getId());
                er.setType("Contains");
                er.setTypeGroup(RelationTypeGroup.COMMON);
                relationService.saveRelation(tenantId, er);
                long temperature = (long) (Math.random() * 100);
                temperatures.add(temperature);
                if (temperature > 45) {
                    highTemperatures.add(temperature);
                }
            }
        }
    }

    @Test
    public void testSimpleFindEntityDataByQuery() throws InterruptedException {
        List<Device> devices = new ArrayList<>();
        for (int i = 0; i < 97; i++) {
            Device device = new Device();
            device.setTenantId(tenantId);
            device.setName("Device" + i);
            device.setType("default");
            device.setLabel("testLabel" + (int) (Math.random() * 1000));
            //TO make sure devices have different created time
            Thread.sleep(1);
            devices.add(deviceService.saveDevice(device));
        }

        DeviceTypeFilter filter = new DeviceTypeFilter();
        filter.setDeviceType("default");
        filter.setDeviceNameFilter("");

        EntityDataSortOrder sortOrder = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.ENTITY_FIELD, "createdTime"), EntityDataSortOrder.Direction.ASC
        );
        EntityDataPageLink pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        List<EntityKey> entityFields = Collections.singletonList(new EntityKey(EntityKeyType.ENTITY_FIELD, "name"));

        EntityDataQuery query = new EntityDataQuery(filter, pageLink, entityFields, null, null);
        PageData<EntityData> data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);

        Assert.assertEquals(97, data.getTotalElements());
        Assert.assertEquals(10, data.getTotalPages());
        Assert.assertTrue(data.hasNext());
        Assert.assertEquals(10, data.getData().size());

        List<EntityData> loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(97, loadedEntities.size());

        List<EntityId> loadedIds = loadedEntities.stream().map(EntityData::getEntityId).collect(Collectors.toList());
        List<EntityId> deviceIds = devices.stream().map(Device::getId).collect(Collectors.toList());
        deviceIds.sort(Comparator.comparing(EntityId::getId));
        loadedIds.sort(Comparator.comparing(EntityId::getId));
        Assert.assertEquals(deviceIds, loadedIds);

        List<String> loadedNames = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ENTITY_FIELD).get("name").getValue()).collect(Collectors.toList());
        List<String> deviceNames = devices.stream().map(Device::getName).collect(Collectors.toList());

        Collections.sort(loadedNames);
        Collections.sort(deviceNames);
        Assert.assertEquals(deviceNames, loadedNames);

        sortOrder = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.ENTITY_FIELD, "name"), EntityDataSortOrder.Direction.DESC
        );

        pageLink = new EntityDataPageLink(10, 0, "device1", sortOrder);
        query = new EntityDataQuery(filter, pageLink, entityFields, null, null);
        data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
        Assert.assertEquals(11, data.getTotalElements());
        Assert.assertEquals("Device19", data.getData().get(0).getLatest().get(EntityKeyType.ENTITY_FIELD).get("name").getValue());

        deviceService.deleteDevicesByTenantId(tenantId);
    }

    @Test
    public void testFindEntityDataByQueryWithAttributes() throws ExecutionException, InterruptedException {

        List<Device> devices = new ArrayList<>();
        List<Long> temperatures = new ArrayList<>();
        List<Long> highTemperatures = new ArrayList<>();
        for (int i = 0; i < 67; i++) {
            Device device = new Device();
            device.setTenantId(tenantId);
            device.setName("Device" + i);
            device.setType("default");
            device.setLabel("testLabel" + (int) (Math.random() * 1000));
            devices.add(deviceService.saveDevice(device));
            //TO make sure devices have different created time
            Thread.sleep(1);
            long temperature = (long) (Math.random() * 100);
            temperatures.add(temperature);
            if (temperature > 45) {
                highTemperatures.add(temperature);
            }
        }

        List<ListenableFuture<List<Void>>> attributeFutures = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            attributeFutures.add(saveLongAttribute(device.getId(), "temperature", temperatures.get(i), DataConstants.CLIENT_SCOPE));
        }
        Futures.successfulAsList(attributeFutures).get();

        DeviceTypeFilter filter = new DeviceTypeFilter();
        filter.setDeviceType("default");
        filter.setDeviceNameFilter("");

        EntityDataSortOrder sortOrder = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.ENTITY_FIELD, "createdTime"), EntityDataSortOrder.Direction.ASC
        );
        EntityDataPageLink pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        List<EntityKey> entityFields = Collections.singletonList(new EntityKey(EntityKeyType.ENTITY_FIELD, "name"));
        List<EntityKey> latestValues = Collections.singletonList(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));

        EntityDataQuery query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, null);
        PageData<EntityData> data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);

        List<EntityData> loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(67, loadedEntities.size());
        List<String> loadedTemperatures = new ArrayList<>();
        for (Device device : devices) {
            loadedTemperatures.add(loadedEntities.stream().filter(entityData -> entityData.getEntityId().equals(device.getId())).findFirst().orElse(null)
                    .getLatest().get(EntityKeyType.ATTRIBUTE).get("temperature").getValue());
        }
        List<String> deviceTemperatures = temperatures.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());
        Assert.assertEquals(deviceTemperatures, loadedTemperatures);

        pageLink = new EntityDataPageLink(10, 0, null, sortOrder);
        KeyFilter highTemperatureFilter = new KeyFilter();
        highTemperatureFilter.setKey(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));
        NumericFilterPredicate predicate = new NumericFilterPredicate();
        predicate.setValue(FilterPredicateValue.fromDouble(45));
        predicate.setOperation(NumericFilterPredicate.NumericOperation.GREATER);
        highTemperatureFilter.setPredicate(predicate);
        List<KeyFilter> keyFilters = Collections.singletonList(highTemperatureFilter);

        query = new EntityDataQuery(filter, pageLink, entityFields, latestValues, keyFilters);

        data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);

        loadedEntities = new ArrayList<>(data.getData());
        while (data.hasNext()) {
            query = query.next();
            data = entityService.findEntityDataByQuery(tenantId, new CustomerId(CustomerId.NULL_UUID), query);
            loadedEntities.addAll(data.getData());
        }
        Assert.assertEquals(highTemperatures.size(), loadedEntities.size());

        List<String> loadedHighTemperatures = loadedEntities.stream().map(entityData ->
                entityData.getLatest().get(EntityKeyType.ATTRIBUTE).get("temperature").getValue()).collect(Collectors.toList());
        List<String> deviceHighTemperatures = highTemperatures.stream().map(aLong -> Long.toString(aLong)).collect(Collectors.toList());

        Assert.assertEquals(deviceHighTemperatures, loadedHighTemperatures);

        deviceService.deleteDevicesByTenantId(tenantId);
    }

    private ListenableFuture<List<Void>> saveLongAttribute(EntityId entityId, String key, long value, String scope) {
        KvEntry attrValue = new LongDataEntry(key, value);
        AttributeKvEntry attr = new BaseAttributeKvEntry(attrValue, 42L);
        return attributesService.save(SYSTEM_TENANT_ID, entityId, scope, Collections.singletonList(attr));
    }
}
