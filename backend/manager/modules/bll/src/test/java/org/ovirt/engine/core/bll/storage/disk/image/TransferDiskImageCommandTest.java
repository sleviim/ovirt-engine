package org.ovirt.engine.core.bll.storage.disk.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.ovirt.engine.core.bll.BaseCommandTest;
import org.ovirt.engine.core.bll.ValidateTestUtils;
import org.ovirt.engine.core.bll.ValidationResult;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.bll.validator.storage.DiskImagesValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskValidator;
import org.ovirt.engine.core.bll.validator.storage.StorageDomainValidator;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.TransferDiskImageParameters;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.StorageType;
import org.ovirt.engine.core.common.businessentities.storage.TransferType;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.DiskImageDao;
import org.ovirt.engine.core.dao.ImageTransferDao;
import org.ovirt.engine.core.dao.StorageDomainDao;

@RunWith(MockitoJUnitRunner.class)
public class TransferDiskImageCommandTest extends BaseCommandTest {

    @Mock
    DiskValidator diskValidator;

    @Mock
    DiskImagesValidator diskImagesValidator;

    @Mock
    StorageDomainValidator storageDomainValidator;

    @Mock
    StorageDomainDao storageDomainDao;

    @Mock
    DiskImageDao diskImageDao;

    @Mock
    ImageTransferDao imageTransferDao;

    @Spy
    @InjectMocks
    protected TransferDiskImageCommand<TransferDiskImageParameters> transferImageCommand =
            new TransferDiskImageCommand<>(new TransferDiskImageParameters(), null);

    @Before
    public void setUp() {
        doNothing().when(transferImageCommand).createImage();
        doNothing().when(transferImageCommand).persistCommand(any(), anyBoolean());
        doNothing().when(transferImageCommand).lockImage();
        doNothing().when(transferImageCommand).startImageTransferSession();
    }

    protected void initializeSuppliedImage() {
        initSuppliedImage();

        DiskImage diskImage = new DiskImage();
        diskImage.setActive(true);
        diskImage.setImageId(transferImageCommand.getParameters().getImageId());
        diskImage.setStorageIds(new ArrayList<>(Collections.singletonList(Guid.newGuid())));
        diskImage.setStorageTypes(new ArrayList<>(Collections.singletonList(StorageType.NFS)));
        doReturn(diskImage).when(diskImageDao).get(any());

        doReturn(diskValidator).when(transferImageCommand).getDiskValidator(any());
        doReturn(diskImagesValidator).when(transferImageCommand).getDiskImagesValidator(any());
        doReturn(storageDomainValidator).when(transferImageCommand).getStorageDomainValidator(any());
    }

    protected void initSuppliedImage() {
        Guid imageId = Guid.newGuid();
        transferImageCommand.getParameters().setImageId(imageId);
    }

    private DiskImage initReadyImageForUpload() {
        Guid imageId = Guid.newGuid();
        Guid sdId = Guid.newGuid();

        ArrayList<Guid> sdList = new ArrayList<>();
        sdList.add(sdId);

        DiskImage readyImage = new DiskImage();
        readyImage.setImageId(imageId);
        readyImage.setStorageIds(sdList);
        readyImage.setStorageTypes(new ArrayList<>(Collections.singletonList(StorageType.NFS)));
        readyImage.setSize(1024L);

        doReturn(readyImage).when(transferImageCommand).getDiskImage();
        return readyImage;
    }

    /************
     * Validation
     ************/
    @Test
    public void testValidationCallOnCreateImage() {
        doReturn(true).when(transferImageCommand).validateCreateImage();
        transferImageCommand.validate();
        verify(transferImageCommand, times(1)).validateCreateImage();
    }

    @Test
    public void testValidationCallOnSuppliedImage() {
        Guid imageId = Guid.newGuid();
        transferImageCommand.getParameters().setImageId(imageId);
        doReturn(true).when(transferImageCommand).validateImageTransfer();

        transferImageCommand.validate();
        verify(transferImageCommand, times(1)).validateImageTransfer();
    }

    @Test
    public void testFailOnDownloadWithoutImage() {
        transferImageCommand.getParameters().setTransferType(TransferType.Download);
        ValidateTestUtils.runAndAssertValidateFailure(transferImageCommand,
                EngineMessage.ACTION_TYPE_FAILED_IMAGE_NOT_SPECIFIED_FOR_DOWNLOAD);
    }

    @Test
    public void validate() {
        initializeSuppliedImage();
        assertTrue(transferImageCommand.validate());
    }

    @Test
    public void validateCantUploadLockedImage() {
        initializeSuppliedImage();
        doReturn(new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_DISKS_LOCKED, ""))
                .when(diskImagesValidator)
                .diskImagesNotLocked();

        transferImageCommand.validate();
        ValidateTestUtils.assertValidationMessages(
                "Can't start a transfer for a locked image.",
                transferImageCommand,
                EngineMessage.ACTION_TYPE_FAILED_DISKS_LOCKED);
    }

    @Test
    public void validateCantUploadDiskAttached() {
        initializeSuppliedImage();
        doReturn(new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_DISK_PLUGGED_TO_NON_DOWN_VMS, ""))
                .when(diskValidator)
                .isDiskPluggedToAnyNonDownVm(false);

        transferImageCommand.validate();
        ValidateTestUtils.assertValidationMessages(
                "Can't start a transfer for an image that is attached to any VMs.",
                transferImageCommand,
                EngineMessage.ACTION_TYPE_FAILED_DISK_PLUGGED_TO_NON_DOWN_VMS);
    }

    @Test
    public void validateCantUploadDiskNotExists() {
        initializeSuppliedImage();
        doReturn(new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_DISK_NOT_EXIST, ""))
                .when(diskValidator)
                .isDiskExists();

        transferImageCommand.validate();
        ValidateTestUtils.assertValidationMessages(
                "Can't start a transfer for image that doesn't exist.",
                transferImageCommand,
                EngineMessage.ACTION_TYPE_FAILED_DISK_NOT_EXIST);
    }

    @Test
    public void validateCantUploadIllegalImage() {
        initializeSuppliedImage();
        doReturn(new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_DISKS_ILLEGAL, ""))
                .when(diskImagesValidator)
                .diskImagesNotIllegal();

        transferImageCommand.validate();
        ValidateTestUtils.assertValidationMessages(
                "Can't start a transfer for an illegal image.",
                transferImageCommand,
                EngineMessage.ACTION_TYPE_FAILED_DISKS_ILLEGAL);
    }

    @Test
    public void validateCantUploadToNonActiveDomain() {
        initializeSuppliedImage();
        doReturn(new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_STORAGE_DOMAIN_STATUS_ILLEGAL2, ""))
                .when(storageDomainValidator)
                .isDomainExistAndActive();

        ValidateTestUtils.runAndAssertValidateFailure(
                "Can't start a transfer to a non-active storage domain.",
                transferImageCommand,
                EngineMessage.ACTION_TYPE_FAILED_STORAGE_DOMAIN_STATUS_ILLEGAL2);
    }

    /*****************
     Command execution
     *****************/
    @Test
    public void testCreatingImageIfNotSupplied() {
        transferImageCommand.executeCommand();

        // Make sure an image is created.
        verify(transferImageCommand, times(1)).createImage();

        // Make sure that a transfer session won't start yet.
        verify(transferImageCommand, never()).handleImageIsReadyForTransfer();
    }

    @Test
    public void testNotCreatingImageIfSupplied() {
        Guid suppliedImageId = Guid.newGuid();
        doNothing().when(transferImageCommand).handleImageIsReadyForTransfer();
        transferImageCommand.getParameters().setImageId(suppliedImageId);
        transferImageCommand.executeCommand();

        // Make sure no image is created if an image Guid is supplied.
        verify(transferImageCommand, never()).createImage();

        // Make sure that a transfer session will start.
        verify(transferImageCommand, times(1)).handleImageIsReadyForTransfer();
    }

    @Test
    public void testFailsDownloadExecutionWithoutImage() {
        transferImageCommand.getParameters().setTransferType(TransferType.Download);
        transferImageCommand.executeCommand();

        ValidateTestUtils.runAndAssertValidateFailure(transferImageCommand,
                EngineMessage.ACTION_TYPE_FAILED_IMAGE_NOT_SPECIFIED_FOR_DOWNLOAD);
    }

    /*********************************
     * Handling ready image to upload
     ********************************/
    @Test
    public void testParamsUpdated() {
        DiskImage readyImage = initReadyImageForUpload();

        transferImageCommand.handleImageIsReadyForTransfer();

        assertTrue(transferImageCommand.getParameters().getStorageDomainId().equals(readyImage.getStorageIds().get(0)));
        assertTrue(transferImageCommand.getParameters().getTransferSize() == readyImage.getSize());
    }

    @Test
    public void testCommandPersistedWithParamUpdates() {
        DiskImage readyImage = initReadyImageForUpload();

        TransferDiskImageParameters params = spy(new TransferDiskImageParameters());
        doReturn(params).when(transferImageCommand).getParameters();

        transferImageCommand.handleImageIsReadyForTransfer();

        // Verify that persistCommand is being called after each of the params changes.
        InOrder inOrder = inOrder(params, transferImageCommand);
        inOrder.verify(params).setStorageDomainId(any());
        inOrder.verify(transferImageCommand).persistCommand(any(), anyBoolean());

        inOrder = inOrder(params, transferImageCommand);
        inOrder.verify(params).setTransferSize(anyLong());
        inOrder.verify(transferImageCommand).persistCommand(any(), anyBoolean());
    }

    /**********
     * Other
     *********/
    @Test
    public void testUploadIsDefaultTransferType() {
        assertEquals(transferImageCommand.getParameters().getTransferType(), TransferType.Upload);
    }

    @Test
    public void testPermissionSubjectOnProvidedImage() {
        initializeSuppliedImage();
        assertEquals(transferImageCommand.getPermissionCheckSubjects().get(0),
                new PermissionSubject(transferImageCommand.getParameters().getImageGroupID(),
                        VdcObjectType.Disk,
                        ActionGroup.EDIT_DISK_PROPERTIES));
    }

    @Test
    public void testPermissionSubjectOnNewImage() {
        assertEquals(transferImageCommand.getPermissionCheckSubjects().get(0),
                new PermissionSubject(transferImageCommand.getParameters().getImageId(),
                        VdcObjectType.Storage,
                        ActionGroup.CREATE_DISK));
    }
}
