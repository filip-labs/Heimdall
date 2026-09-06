package main

import "fmt"

const maxVINIndex = 99999999999999

func generateVIN(index int) (string, error) {
	if index <= 0 {
		return "", fmt.Errorf("vehicle index must be greater than 0")
	}

	if index > maxVINIndex {
		return "", fmt.Errorf(
			"vehicle index %d exceeds VIN capacity",
			index,
		)
	}

	vin := fmt.Sprintf("7FC%014d", index)

	if len(vin) != 17 {
		return "", fmt.Errorf(
			"generated VIN %q has invalid length %d",
			vin,
			len(vin),
		)
	}

	return vin, nil
}
