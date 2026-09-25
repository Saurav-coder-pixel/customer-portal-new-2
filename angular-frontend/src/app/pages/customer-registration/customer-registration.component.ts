import { Component, OnInit } from "@angular/core";
import { FormBuilder, FormGroup, FormArray, Validators } from "@angular/forms";
import { CustomerService } from "../../services/customer.service";
import { DocumentService } from "../../services/document.service";

type Tab = "new" | "old" | "ownership";
type SubTab = "global" | "handling";

@Component({
  selector: "app-customer-registration",
  standalone: false,
  templateUrl: "./customer-registration.component.html",
  styleUrls: ["./customer-registration.component.css"]
})
export class CustomerRegistrationComponent implements OnInit {
  activeTab: Tab = "new";
  activeSubTab: SubTab = "global";

  // New Registration
  newForm!: FormGroup;
  zones: string[] = [];
  divisions: string[] = [];
  generatedCode = "";
  panFile: File | null = null;
  gstinFiles: (File | null)[] = [];
  newLoading = false;
  newError = "";
  newSuccess = "";

  // Old Entry Update
  oldForm!: FormGroup;
  oldLookupCode = "";
  oldLookupLoading = false;
  oldLookupError = "";
  oldLookupSuccess = "";
  oldData: any = null;
  oldUpdateLoading = false;
  oldUpdateError = "";
  oldUpdateSuccess = "";

  constructor(
    private fb: FormBuilder,
    private customerService: CustomerService,
    private documentService: DocumentService
  ) {}

  ngOnInit(): void {
    this.buildNewForm();
    this.buildOldForm();
    this.loadMasterData();
  }

  // ======================== MASTER DATA ========================

  loadMasterData(): void {
    this.customerService.getMasterData().subscribe({
      next: (data: any) => {
        this.zones     = data?.zones     || [];
        this.divisions = data?.divisions || [];
      },
      error: () => {}
    });
  }

  // ======================== NEW REGISTRATION ========================

  buildNewForm(): void {
    this.newForm = this.fb.group({
      companyName:        ["", Validators.required],
      address:            ["", Validators.required],
      city:               ["", Validators.required],
      pincode:            ["", [Validators.required, Validators.pattern(/^\d{6}$/)]],
      email:              ["", [Validators.required, Validators.email]],
      mobile:             ["", [Validators.required, Validators.pattern(/^\d{10}$/)]],
      panNumber:          ["", [Validators.required, Validators.pattern(/^[A-Z]{5}[0-9]{4}[A-Z]$/)]],
      zone:               ["", Validators.required],
      operatingDivision:  ["", Validators.required],
      gstins:             this.fb.array([this.createGstinGroup()])
    });
  }

  get gstinsArray(): FormArray {
    return this.newForm.get("gstins") as FormArray;
  }

  createGstinGroup(): FormGroup {
    return this.fb.group({
      gstinNumber: ["", [Validators.required, Validators.pattern(/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][A-Z0-9]Z[A-Z0-9]$/)]],
      state:       [""],
      stateCode:   [""]
    });
  }

  addGstin(): void {
    this.gstinsArray.push(this.createGstinGroup());
    this.gstinFiles.push(null);
  }

  removeGstin(i: number): void {
    if (this.gstinsArray.length > 1) {
      this.gstinsArray.removeAt(i);
      this.gstinFiles.splice(i, 1);
    }
  }

  onGstinInput(i: number, event: Event): void {
    const val = (event.target as HTMLInputElement).value.trim().toUpperCase();
    const grp = this.gstinsArray.at(i) as FormGroup;
    grp.patchValue({ gstinNumber: val });
    if (val.length >= 2) {
      const code = val.substring(0, 2);
      const stateNames: Record<string, string> = {
        "01":"Jammu and Kashmir","02":"Himachal Pradesh","03":"Punjab","04":"Chandigarh",
        "05":"Uttarakhand","06":"Haryana","07":"Delhi","08":"Rajasthan","09":"Uttar Pradesh",
        "10":"Bihar","11":"Sikkim","12":"Arunachal Pradesh","13":"Nagaland","14":"Manipur",
        "15":"Mizoram","16":"Tripura","17":"Meghalaya","18":"Assam","19":"West Bengal",
        "20":"Jharkhand","21":"Odisha","22":"Chhattisgarh","23":"Madhya Pradesh","24":"Gujarat",
        "25":"Daman and Diu","26":"Dadra and Nagar Haveli","27":"Maharashtra","28":"Andhra Pradesh",
        "29":"Karnataka","30":"Goa","31":"Lakshadweep","32":"Kerala","33":"Tamil Nadu",
        "34":"Puducherry","35":"Andaman and Nicobar Islands","36":"Telangana",
        "37":"Andhra Pradesh (New)","38":"Ladakh"
      };
      grp.patchValue({ stateCode: code, state: stateNames[code] || "" });
    }
  }

  onPanFileChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.panFile = input.files?.[0] || null;
  }

  onGstinFileChange(i: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    this.gstinFiles[i] = input.files?.[0] || null;
  }

  generateCode(): void {
    const companyName = this.newForm.get("companyName")?.value?.trim();
    if (!companyName) { this.newError = "Enter company name to generate code."; return; }
    this.customerService.generateCode(companyName, this.activeSubTab).subscribe({
      next: (res: any) => { this.generatedCode = res.code; },
      error: () => { this.newError = "Could not generate code."; }
    });
  }

  submitNew(): void {
    this.newError = "";
    this.newSuccess = "";
    if (this.newForm.invalid) {
      this.newForm.markAllAsTouched();
      this.newError = "Please fill all required fields correctly.";
      return;
    }
    this.newLoading = true;
    const fd = new FormData();
    const val = this.newForm.value;
    fd.append("customerType", this.activeSubTab);
    fd.append("companyName", val.companyName);
    fd.append("address", val.address);
    fd.append("city", val.city);
    fd.append("pincode", val.pincode);
    fd.append("email", val.email);
    fd.append("mobile", val.mobile);
    fd.append("panNumber", val.panNumber.toUpperCase());
    fd.append("zone", val.zone);
    fd.append("operatingDivision", val.operatingDivision);
    if (this.generatedCode) fd.append("suggestedCode", this.generatedCode);
    fd.append("gstinData", JSON.stringify(val.gstins));
    if (this.panFile) fd.append("panFile", this.panFile);
    val.gstins.forEach((_: any, i: number) => {
      if (this.gstinFiles[i]) fd.append("gstinFiles", this.gstinFiles[i]!);
    });

    this.customerService.registerNewEntry(fd).subscribe({
      next: (res: any) => {
        this.newLoading = false;
        this.newSuccess = res.message || "Registration successful!";
        this.newForm.reset();
        this.buildNewForm();
        this.generatedCode = "";
        this.panFile = null;
        this.gstinFiles = [];
      },
      error: (err: any) => {
        this.newLoading = false;
        this.newError = err?.error?.message || "Registration failed.";
      }
    });
  }

  // ======================== OLD ENTRY UPDATE ========================

  buildOldForm(): void {
    this.oldForm = this.fb.group({
      customerCode: [""],
      companyName:  [""],
      address:      [""],
      city:         [""],
      panNumber:    [""],
      gstinNumbers: [""]
    });
  }

  lookupOld(): void {
    this.oldLookupError = "";
    this.oldLookupSuccess = "";
    this.oldData = null;
    const code = this.oldLookupCode?.trim().toUpperCase();
    if (!code) { this.oldLookupError = "Enter a customer code."; return; }
    this.oldLookupLoading = true;
    this.customerService.lookupOldCustomer(code).subscribe({
      next: (data: any) => {
        this.oldLookupLoading = false;
        this.oldData = data;
        this.oldForm.patchValue({
          customerCode: data.customerCode,
          companyName:  data.companyName,
          address:      data.address,
          city:         data.city,
          panNumber:    data.panNumber,
          gstinNumbers: data.gstinNumbers
        });
      },
      error: (err: any) => {
        this.oldLookupLoading = false;
        this.oldLookupError = err?.error?.message || "Code not found.";
      }
    });
  }

  updateOld(): void {
    this.oldUpdateError = "";
    this.oldUpdateSuccess = "";
    if (!this.oldData) return;
    this.oldUpdateLoading = true;
    this.customerService.updateOldCustomer(this.oldForm.value).subscribe({
      next: (res: any) => {
        this.oldUpdateLoading = false;
        this.oldUpdateSuccess = res.message || "Updated successfully.";
      },
      error: (err: any) => {
        this.oldUpdateLoading = false;
        this.oldUpdateError = err?.error?.message || "Update failed.";
      }
    });
  }
}